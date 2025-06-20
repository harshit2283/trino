/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.deltalake.transactionlog.checkpoint;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.math.LongMath;
import io.airlift.log.Logger;
import io.trino.filesystem.TrinoInputFile;
import io.trino.parquet.ParquetReaderOptions;
import io.trino.plugin.base.metrics.FileFormatDataSourceStats;
import io.trino.plugin.deltalake.DeltaHiveTypeTranslator;
import io.trino.plugin.deltalake.DeltaLakeColumnHandle;
import io.trino.plugin.deltalake.DeltaLakeColumnMetadata;
import io.trino.plugin.deltalake.transactionlog.AddFileEntry;
import io.trino.plugin.deltalake.transactionlog.DeletionVectorEntry;
import io.trino.plugin.deltalake.transactionlog.DeltaLakeTransactionLogEntry;
import io.trino.plugin.deltalake.transactionlog.MetadataEntry;
import io.trino.plugin.deltalake.transactionlog.ProtocolEntry;
import io.trino.plugin.deltalake.transactionlog.RemoveFileEntry;
import io.trino.plugin.deltalake.transactionlog.SidecarEntry;
import io.trino.plugin.deltalake.transactionlog.TransactionEntry;
import io.trino.plugin.deltalake.transactionlog.statistics.DeltaLakeParquetFileStatistics;
import io.trino.plugin.hive.HiveColumnHandle;
import io.trino.plugin.hive.HiveColumnHandle.ColumnType;
import io.trino.plugin.hive.HiveColumnProjectionInfo;
import io.trino.plugin.hive.parquet.ParquetPageSourceFactory;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.block.LongArrayBlock;
import io.trino.spi.block.RowBlock;
import io.trino.spi.block.SqlRow;
import io.trino.spi.block.ValueBlock;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.SourcePage;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.TimestampWithTimeZoneType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;
import io.trino.spi.type.TypeSignature;
import jakarta.annotation.Nullable;
import org.joda.time.DateTimeZone;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.collect.MoreCollectors.toOptional;
import static io.trino.plugin.deltalake.DeltaLakeColumnType.REGULAR;
import static io.trino.plugin.deltalake.DeltaLakeErrorCode.DELTA_LAKE_INVALID_SCHEMA;
import static io.trino.plugin.deltalake.transactionlog.DeltaLakeSchemaSupport.extractSchema;
import static io.trino.plugin.deltalake.transactionlog.DeltaLakeSchemaSupport.isDeletionVectorEnabled;
import static io.trino.plugin.deltalake.transactionlog.TransactionLogAccess.columnsWithStats;
import static io.trino.plugin.deltalake.transactionlog.TransactionLogParser.START_OF_MODERN_ERA_EPOCH_DAY;
import static io.trino.plugin.deltalake.transactionlog.TransactionLogUtil.canonicalizePartitionValues;
import static io.trino.plugin.deltalake.transactionlog.checkpoint.CheckpointEntryIterator.EntryType.ADD;
import static io.trino.plugin.deltalake.transactionlog.checkpoint.CheckpointEntryIterator.EntryType.METADATA;
import static io.trino.plugin.deltalake.transactionlog.checkpoint.CheckpointEntryIterator.EntryType.PROTOCOL;
import static io.trino.plugin.deltalake.transactionlog.checkpoint.CheckpointEntryIterator.EntryType.REMOVE;
import static io.trino.plugin.deltalake.transactionlog.checkpoint.CheckpointEntryIterator.EntryType.SIDECAR;
import static io.trino.plugin.deltalake.transactionlog.checkpoint.CheckpointEntryIterator.EntryType.TRANSACTION;
import static io.trino.plugin.deltalake.util.DeltaLakeDomains.partitionMatchesPredicate;
import static io.trino.plugin.hive.util.HiveTypeTranslator.toHiveType;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static io.trino.spi.type.TimeZoneKey.UTC_KEY;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.Timestamps.MICROSECONDS_PER_MILLISECOND;
import static io.trino.spi.type.Timestamps.MILLISECONDS_PER_DAY;
import static io.trino.spi.type.TypeUtils.readNativeValue;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.lang.Math.floorDiv;
import static java.lang.String.format;
import static java.math.RoundingMode.UNNECESSARY;
import static java.util.Objects.requireNonNull;

public class CheckpointEntryIterator
        extends AbstractIterator<DeltaLakeTransactionLogEntry>
{
    public enum EntryType
    {
        TRANSACTION("txn"),
        ADD("add"),
        REMOVE("remove"),
        METADATA("metadata"),
        PROTOCOL("protocol"),
        SIDECAR("sidecar"),
        /**/;

        private final String columnName;

        EntryType(String columnName)
        {
            this.columnName = columnName;
        }

        public String getColumnName()
        {
            return columnName;
        }
    }

    private static final Logger log = Logger.get(CheckpointEntryIterator.class);

    private final String checkpointPath;
    private final ConnectorSession session;
    private final ConnectorPageSource pageSource;
    private final MapType stringMap;
    private final ArrayType stringList;
    private final Queue<DeltaLakeTransactionLogEntry> nextEntries;
    private final List<CheckpointFieldExtractor> extractors;
    private final boolean checkpointRowStatisticsWritingEnabled;
    private final TupleDomain<DeltaLakeColumnHandle> partitionConstraint;
    private final Optional<RowType> txnType;
    private final Optional<RowType> addType;
    private final Optional<RowType> addDeletionVectorType;
    private final Optional<RowType> addParsedStatsFieldType;
    private final Optional<RowType> removeType;
    private final Optional<RowType> removeDeletionVectorType;
    private final Optional<RowType> metadataType;
    private final Optional<RowType> protocolType;
    private final Optional<RowType> sidecarType;

    private MetadataEntry metadataEntry;
    private ProtocolEntry protocolEntry;
    private boolean deletionVectorsEnabled;
    private List<DeltaLakeColumnMetadata> schema;
    private List<DeltaLakeColumnMetadata> columnsWithMinMaxStats;
    private SourcePage page;
    private int pagePosition;

    public CheckpointEntryIterator(
            TrinoInputFile checkpoint,
            ConnectorSession session,
            long fileSize,
            CheckpointSchemaManager checkpointSchemaManager,
            TypeManager typeManager,
            Set<EntryType> fields,
            Optional<MetadataEntry> metadataEntry,
            Optional<ProtocolEntry> protocolEntry,
            FileFormatDataSourceStats stats,
            ParquetReaderOptions parquetReaderOptions,
            boolean checkpointRowStatisticsWritingEnabled,
            int domainCompactionThreshold,
            TupleDomain<DeltaLakeColumnHandle> partitionConstraint,
            Optional<Predicate<String>> addStatsMinMaxColumnFilter)
    {
        this.checkpointPath = checkpoint.location().toString();
        this.session = requireNonNull(session, "session is null");
        this.stringList = (ArrayType) typeManager.getType(TypeSignature.arrayType(VARCHAR.getTypeSignature()));
        this.stringMap = (MapType) typeManager.getType(TypeSignature.mapType(VARCHAR.getTypeSignature(), VARCHAR.getTypeSignature()));
        this.checkpointRowStatisticsWritingEnabled = checkpointRowStatisticsWritingEnabled;
        this.partitionConstraint = requireNonNull(partitionConstraint, "partitionConstraint is null");
        requireNonNull(addStatsMinMaxColumnFilter, "addStatsMinMaxColumnFilter is null");
        checkArgument(!fields.isEmpty(), "fields is empty");
        // ADD requires knowing the metadata in order to figure out the Parquet schema
        if (fields.contains(ADD)) {
            checkArgument(metadataEntry.isPresent(), "Metadata entry must be provided when reading ADD entries from Checkpoint files");
            this.metadataEntry = metadataEntry.get();
            checkArgument(protocolEntry.isPresent(), "Protocol entry must be provided when reading ADD entries from Checkpoint files");
            this.protocolEntry = protocolEntry.get();
            deletionVectorsEnabled = isDeletionVectorEnabled(this.metadataEntry, this.protocolEntry);
            checkArgument(addStatsMinMaxColumnFilter.isPresent(), "addStatsMinMaxColumnFilter must be provided when reading ADD entries from Checkpoint files");
            this.schema = extractSchema(this.metadataEntry, this.protocolEntry, typeManager);
            this.columnsWithMinMaxStats = columnsWithStats(schema, this.metadataEntry.getOriginalPartitionColumns());
            Predicate<String> columnStatsFilterFunction = addStatsMinMaxColumnFilter.orElseThrow();
            this.columnsWithMinMaxStats = columnsWithMinMaxStats.stream()
                    .filter(column -> columnStatsFilterFunction.test(column.name()))
                    .collect(toImmutableList());
        }

        ImmutableList.Builder<HiveColumnHandle> columnsBuilder = ImmutableList.builderWithExpectedSize(fields.size());
        ImmutableList.Builder<TupleDomain<HiveColumnHandle>> disjunctDomainsBuilder = ImmutableList.builderWithExpectedSize(fields.size());
        for (EntryType field : fields) {
            HiveColumnHandle column = buildColumnHandle(field, checkpointSchemaManager, this.metadataEntry, this.protocolEntry, addStatsMinMaxColumnFilter).toHiveColumnHandle();
            columnsBuilder.add(column);
            disjunctDomainsBuilder.add(buildTupleDomainColumnHandle(field, column));
        }
        List<HiveColumnHandle> columns = columnsBuilder.build();

        this.pageSource = ParquetPageSourceFactory.createPageSource(
                checkpoint,
                0,
                fileSize,
                columns,
                disjunctDomainsBuilder.build(), // OR-ed condition
                true,
                DateTimeZone.UTC,
                stats,
                parquetReaderOptions,
                Optional.empty(),
                domainCompactionThreshold,
                OptionalLong.of(fileSize));

        try {
            this.nextEntries = new ArrayDeque<>();
            this.extractors = fields.stream()
                    .map(this::createCheckpointFieldExtractor)
                    .collect(toImmutableList());
            txnType = getParquetType(fields, TRANSACTION, columns);
            addType = getAddParquetTypeContainingField(fields, "path", columns);
            addDeletionVectorType = addType.flatMap(type -> getOptionalFieldType(type, "deletionVector"));
            addParsedStatsFieldType = addType.flatMap(type -> getOptionalFieldType(type, "stats_parsed"));
            removeType = getParquetType(fields, REMOVE, columns);
            removeDeletionVectorType = removeType.flatMap(type -> getOptionalFieldType(type, "deletionVector"));
            metadataType = getParquetType(fields, METADATA, columns);
            protocolType = getParquetType(fields, PROTOCOL, columns);
            sidecarType = getParquetType(fields, SIDECAR, columns);
        }
        catch (Exception e) {
            try {
                this.pageSource.close();
            }
            catch (Exception _) {
            }
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Error while initializing the checkpoint entry iterator for the file %s".formatted(checkpoint.location()), e);
        }
    }

    private static Optional<RowType> getOptionalFieldType(RowType type, String fieldName)
    {
        return type.getFields().stream()
                .filter(field -> field.getName().orElseThrow().equals(fieldName))
                .collect(toOptional())
                .map(RowType.Field::getType)
                .map(RowType.class::cast);
    }

    private static Optional<RowType> getAddParquetTypeContainingField(Set<EntryType> fields, String fieldName, List<HiveColumnHandle> columns)
    {
        return fields.contains(ADD) ?
                columns.stream()
                        .filter(column -> column.getName().equals(ADD.getColumnName()) &&
                                column.getType() instanceof RowType rowType &&
                                rowType.getFields().stream().map(RowType.Field::getName).filter(Optional::isPresent).flatMap(Optional::stream).anyMatch(fieldName::equals))
                        // The field even if it was requested might not exist in Parquet file
                        .collect(toOptional())
                        .map(HiveColumnHandle::getType)
                        .map(RowType.class::cast)
                : Optional.empty();
    }

    private static Optional<RowType> getParquetType(Set<EntryType> fields, EntryType field, List<HiveColumnHandle> columns)
    {
        return fields.contains(field) ? getParquetType(field.getColumnName(), columns).map(RowType.class::cast) : Optional.empty();
    }

    private static Optional<Type> getParquetType(String columnName, List<HiveColumnHandle> columns)
    {
        return columns.stream()
                .filter(column -> column.getName().equals(columnName))
                // The field even if it was requested may not exist in Parquet file
                .collect(toOptional())
                .map(HiveColumnHandle::getType);
    }

    private CheckpointFieldExtractor createCheckpointFieldExtractor(EntryType entryType)
    {
        return switch (entryType) {
            case TRANSACTION -> this::buildTxnEntry;
            case ADD -> new AddFileEntryExtractor();
            case REMOVE -> this::buildRemoveEntry;
            case METADATA -> this::buildMetadataEntry;
            case PROTOCOL -> this::buildProtocolEntry;
            case SIDECAR -> this::buildSidecarEntry;
        };
    }

    private static DeltaLakeColumnHandle buildColumnHandle(
            EntryType entryType,
            CheckpointSchemaManager schemaManager,
            MetadataEntry metadataEntry,
            ProtocolEntry protocolEntry,
            Optional<Predicate<String>> addStatsMinMaxColumnFilter)
    {
        Type type = switch (entryType) {
            case TRANSACTION -> schemaManager.getTxnEntryType();
            case ADD -> schemaManager.getAddEntryType(metadataEntry, protocolEntry, addStatsMinMaxColumnFilter.orElseThrow(), true, true, true);
            case REMOVE -> schemaManager.getRemoveEntryType();
            case METADATA -> schemaManager.getMetadataEntryType();
            case PROTOCOL -> schemaManager.getProtocolEntryType(true, true);
            case SIDECAR -> schemaManager.getSidecarEntryType();
        };
        return new DeltaLakeColumnHandle(entryType.getColumnName(), type, OptionalInt.empty(), entryType.getColumnName(), type, REGULAR, Optional.empty());
    }

    /**
     * Constructs a TupleDomain which filters on a specific required primitive sub-column of the EntryType being
     * not null for effectively pushing down the predicate to the Parquet reader.
     * <p>
     * The particular field we select for each action is a required fields per the Delta Log specification, please see
     * <a href="https://github.com/delta-io/delta/blob/master/PROTOCOL.md#Actions">Delta Lake protocol</a>. This is also enforced when we read entries.
     */
    private TupleDomain<HiveColumnHandle> buildTupleDomainColumnHandle(EntryType entryType, HiveColumnHandle column)
    {
        String field;
        Type type;
        switch (entryType) {
            case TRANSACTION -> {
                field = "version";
                type = BIGINT;
            }
            case ADD, REMOVE, SIDECAR -> {
                field = "path";
                type = VARCHAR;
            }
            case METADATA -> {
                field = "id";
                type = VARCHAR;
            }
            case PROTOCOL -> {
                field = "minReaderVersion";
                type = BIGINT;
            }
            default -> throw new IllegalArgumentException("Unsupported Delta Lake checkpoint entry type: " + entryType);
        }
        HiveColumnHandle handle = new HiveColumnHandle(
                column.getBaseColumnName(),
                column.getBaseHiveColumnIndex(),
                column.getBaseHiveType(),
                column.getBaseType(),
                Optional.of(new HiveColumnProjectionInfo(
                        ImmutableList.of(0), // hiveColumnIndex; we provide fake value because we always find columns by name
                        ImmutableList.of(field),
                        toHiveType(type),
                        type)),
                ColumnType.REGULAR,
                column.getComment());

        ImmutableMap.Builder<HiveColumnHandle, Domain> domains = ImmutableMap.<HiveColumnHandle, Domain>builder()
                .put(handle, Domain.notNull(handle.getType()));
        if (entryType == ADD) {
            partitionConstraint.getDomains().orElseThrow().forEach((key, value) -> domains.put(toPartitionValuesParsedField(column, key), value));
        }

        return TupleDomain.withColumnDomains(domains.buildOrThrow());
    }

    private static HiveColumnHandle toPartitionValuesParsedField(HiveColumnHandle addColumn, DeltaLakeColumnHandle partitionColumn)
    {
        checkArgument(partitionColumn.isBaseColumn(), "partitionColumn must be a base column: %s", partitionColumn);
        return new HiveColumnHandle(
                addColumn.getBaseColumnName(),
                addColumn.getBaseHiveColumnIndex(),
                addColumn.getBaseHiveType(),
                addColumn.getBaseType(),
                Optional.of(new HiveColumnProjectionInfo(
                        ImmutableList.of(0, 0), // hiveColumnIndex; we provide fake value because we always find columns by name
                        ImmutableList.of("partitionvalues_parsed", partitionColumn.basePhysicalColumnName()),
                        DeltaHiveTypeTranslator.toHiveType(partitionColumn.type()),
                        partitionColumn.type())),
                HiveColumnHandle.ColumnType.REGULAR,
                addColumn.getComment());
    }

    private DeltaLakeTransactionLogEntry buildProtocolEntry(ConnectorSession session, int pagePosition, Block block)
    {
        log.debug("Building protocol entry from %s pagePosition %d", block, pagePosition);
        if (block.isNull(pagePosition)) {
            return null;
        }
        RowType type = protocolType.orElseThrow();
        int minProtocolFields = 2;
        int maxProtocolFields = 4;
        SqlRow protocolEntryRow = getRow(block, pagePosition);
        int fieldCount = protocolEntryRow.getFieldCount();
        log.debug("Block %s has %s fields", block, fieldCount);
        if (fieldCount < minProtocolFields || fieldCount > maxProtocolFields) {
            throw new TrinoException(DELTA_LAKE_INVALID_SCHEMA,
                    format("Expected block %s to have between %d and %d children, but found %s", block, minProtocolFields, maxProtocolFields, fieldCount));
        }

        CheckpointFieldReader protocol = new CheckpointFieldReader(protocolEntryRow, type);
        ProtocolEntry result = new ProtocolEntry(
                protocol.getInt("minReaderVersion"),
                protocol.getInt("minWriterVersion"),
                protocol.getOptionalSet(stringList, "readerFeatures"),
                protocol.getOptionalSet(stringList, "writerFeatures"));
        log.debug("Result: %s", result);
        return DeltaLakeTransactionLogEntry.protocolEntry(result);
    }

    private DeltaLakeTransactionLogEntry buildMetadataEntry(ConnectorSession session, int pagePosition, Block block)
    {
        log.debug("Building metadata entry from %s pagePosition %d", block, pagePosition);
        if (block.isNull(pagePosition)) {
            return null;
        }
        RowType type = metadataType.orElseThrow();
        int metadataFields = 8;
        int formatFields = 2;
        SqlRow metadataEntryRow = getRow(block, pagePosition);
        CheckpointFieldReader metadata = new CheckpointFieldReader(metadataEntryRow, type);
        log.debug("Block %s has %s fields", block, metadataEntryRow.getFieldCount());
        if (metadataEntryRow.getFieldCount() != metadataFields) {
            throw new TrinoException(DELTA_LAKE_INVALID_SCHEMA,
                    format("Expected block %s to have %d children, but found %s", block, metadataFields, metadataEntryRow.getFieldCount()));
        }
        SqlRow formatRow = metadata.getRow("format");
        if (formatRow.getFieldCount() != formatFields) {
            throw new TrinoException(DELTA_LAKE_INVALID_SCHEMA,
                    format("Expected block %s to have %d children, but found %s", formatRow, formatFields, formatRow.getFieldCount()));
        }

        RowType.Field formatField = type.getFields().stream().filter(field -> field.getName().orElseThrow().equals("format")).collect(onlyElement());
        CheckpointFieldReader format = new CheckpointFieldReader(formatRow, (RowType) formatField.getType());
        MetadataEntry result = new MetadataEntry(
                metadata.getString("id"),
                metadata.getString("name"),
                metadata.getString("description"),
                new MetadataEntry.Format(
                        format.getString("provider"),
                        format.getMap(stringMap, "options")),
                metadata.getString("schemaString"),
                metadata.getList(stringList, "partitionColumns"),
                metadata.getMap(stringMap, "configuration"),
                metadata.getLong("createdTime"));
        log.debug("Result: %s", result);
        return DeltaLakeTransactionLogEntry.metadataEntry(result);
    }

    private DeltaLakeTransactionLogEntry buildRemoveEntry(ConnectorSession session, int pagePosition, Block block)
    {
        log.debug("Building remove entry from %s pagePosition %d", block, pagePosition);
        if (block.isNull(pagePosition)) {
            return null;
        }
        RowType type = removeType.orElseThrow();
        int removeFields = 4;
        SqlRow removeEntryRow = getRow(block, pagePosition);
        log.debug("Block %s has %s fields", block, removeEntryRow.getFieldCount());
        if (removeEntryRow.getFieldCount() != removeFields) {
            throw new TrinoException(DELTA_LAKE_INVALID_SCHEMA,
                    format("Expected block %s to have %d children, but found %s", block, removeFields, removeEntryRow.getFieldCount()));
        }
        CheckpointFieldReader remove = new CheckpointFieldReader(removeEntryRow, type);
        Optional<DeletionVectorEntry> deletionVector = Optional.empty();
        if (deletionVectorsEnabled) {
            deletionVector = Optional.ofNullable(remove.getRow("deletionVector"))
                    .map(row -> parseDeletionVectorFromParquet(row, removeDeletionVectorType.orElseThrow()));
        }
        RemoveFileEntry result = new RemoveFileEntry(
                remove.getString("path"),
                remove.getMap(stringMap, "partitionValues"),
                remove.getLong("deletionTimestamp"),
                remove.getBoolean("dataChange"),
                deletionVector);
        log.debug("Result: %s", result);
        return DeltaLakeTransactionLogEntry.removeFileEntry(result);
    }

    private DeltaLakeTransactionLogEntry buildSidecarEntry(ConnectorSession session, int pagePosition, Block block)
    {
        log.debug("Building sidecar entry from %s pagePosition %d", block, pagePosition);
        if (block.isNull(pagePosition)) {
            return null;
        }
        int sidecarFields = 4;
        SqlRow sidecarEntryRow = getRow(block, pagePosition);
        if (sidecarEntryRow.getFieldCount() != sidecarFields) {
            throw new TrinoException(
                    DELTA_LAKE_INVALID_SCHEMA,
                    format("Expected block %s to have %d children, but found %s", block, sidecarFields, sidecarEntryRow.getFieldCount()));
        }
        RowType type = sidecarType.orElseThrow();
        CheckpointFieldReader sidecar = new CheckpointFieldReader(sidecarEntryRow, type);
        SidecarEntry result = new SidecarEntry(
                sidecar.getString("path"),
                sidecar.getLong("sizeInBytes"),
                sidecar.getLong("modificationTime"),
                Optional.ofNullable(sidecar.getMap(stringMap, "tags")));
        return DeltaLakeTransactionLogEntry.sidecarEntry(result);
    }

    private class AddFileEntryExtractor
            implements CheckpointFieldExtractor
    {
        @Nullable
        @Override
        public DeltaLakeTransactionLogEntry getEntry(ConnectorSession session, int pagePosition, Block addBlock)
        {
            log.debug("Building add entry from %s pagePosition %d", addBlock, pagePosition);
            if (addBlock.isNull(pagePosition)) {
                return null;
            }

            // Materialize from Parquet the information needed to build the AddEntry instance
            SqlRow addEntryRow = getRow(addBlock, pagePosition);
            log.debug("Block %s has %s fields", addBlock, addEntryRow.getFieldCount());
            CheckpointFieldReader addReader = new CheckpointFieldReader(addEntryRow, addType.orElseThrow());

            Map<String, String> partitionValues = addReader.getMap(stringMap, "partitionValues");
            Map<String, Optional<String>> canonicalPartitionValues = canonicalizePartitionValues(partitionValues);
            if (!partitionConstraint.isAll() && !partitionMatchesPredicate(canonicalPartitionValues, partitionConstraint.getDomains().orElseThrow())) {
                return null;
            }

            String path = addReader.getString("path");
            long size = addReader.getLong("size");
            long modificationTime = addReader.getLong("modificationTime");
            boolean dataChange = addReader.getBoolean("dataChange");

            Optional<DeletionVectorEntry> deletionVector = Optional.empty();
            if (deletionVectorsEnabled) {
                deletionVector = Optional.ofNullable(addReader.getRow("deletionVector"))
                        .map(row -> parseDeletionVectorFromParquet(row, addDeletionVectorType.orElseThrow()));
            }

            Optional<DeltaLakeParquetFileStatistics> parsedStats = Optional.ofNullable(addReader.getRow("stats_parsed"))
                    .map(row -> parseStatisticsFromParquet(row, addParsedStatsFieldType.orElseThrow()));
            Optional<String> stats = Optional.empty();
            if (parsedStats.isEmpty()) {
                stats = Optional.ofNullable(addReader.getString("stats"));
            }

            Map<String, String> tags = addReader.getMap(stringMap, "tags");
            AddFileEntry result = new AddFileEntry(
                    path,
                    partitionValues,
                    canonicalPartitionValues,
                    size,
                    modificationTime,
                    dataChange,
                    stats,
                    parsedStats,
                    tags,
                    deletionVector);

            log.debug("Result: %s", result);
            return DeltaLakeTransactionLogEntry.addFileEntry(result);
        }
    }

    private static DeletionVectorEntry parseDeletionVectorFromParquet(SqlRow row, RowType type)
    {
        checkArgument(row.getFieldCount() == 5, "Deletion vector entry must have 5 fields");

        CheckpointFieldReader deletionVector = new CheckpointFieldReader(row, type);
        String storageType = deletionVector.getString("storageType");
        String pathOrInlineDv = deletionVector.getString("pathOrInlineDv");
        OptionalInt offset = deletionVector.getOptionalInt("offset");
        int sizeInBytes = deletionVector.getInt("sizeInBytes");
        long cardinality = deletionVector.getLong("cardinality");
        return new DeletionVectorEntry(storageType, pathOrInlineDv, offset, sizeInBytes, cardinality);
    }

    private DeltaLakeParquetFileStatistics parseStatisticsFromParquet(SqlRow statsRow, RowType type)
    {
        CheckpointFieldReader stats = new CheckpointFieldReader(statsRow, type);
        long numRecords = stats.getLong("numRecords");

        Optional<Map<String, Object>> minValues = Optional.empty();
        Optional<Map<String, Object>> maxValues = Optional.empty();
        Optional<Map<String, Object>> nullCount;
        if (!columnsWithMinMaxStats.isEmpty()) {
            minValues = Optional.of(parseMinMax(stats.getRow("minValues"), columnsWithMinMaxStats));
            maxValues = Optional.of(parseMinMax(stats.getRow("maxValues"), columnsWithMinMaxStats));
        }
        nullCount = Optional.of(parseNullCount(stats.getRow("nullCount"), schema));

        return new DeltaLakeParquetFileStatistics(
                Optional.of(numRecords),
                minValues,
                maxValues,
                nullCount);
    }

    private Map<String, Object> parseMinMax(@Nullable SqlRow row, List<DeltaLakeColumnMetadata> eligibleColumns)
    {
        if (row == null) {
            // Statistics were not collected
            return ImmutableMap.of();
        }

        ImmutableMap.Builder<String, Object> values = ImmutableMap.builder();

        for (int i = 0; i < eligibleColumns.size(); i++) {
            DeltaLakeColumnMetadata metadata = eligibleColumns.get(i);
            String name = metadata.physicalName();
            Type type = metadata.physicalColumnType();

            ValueBlock fieldBlock = row.getUnderlyingFieldBlock(i);
            int fieldIndex = row.getUnderlyingFieldPosition(i);
            if (fieldBlock.isNull(fieldIndex)) {
                continue;
            }
            if (type instanceof RowType rowType) {
                if (checkpointRowStatisticsWritingEnabled) {
                    // RowType column statistics are not used for query planning, but need to be copied when writing out new Checkpoint files.
                    values.put(name, rowType.getObject(fieldBlock, fieldIndex));
                }
                continue;
            }
            if (type instanceof TimestampWithTimeZoneType) {
                long epochMillis = LongMath.divide((long) readNativeValue(TIMESTAMP_MILLIS, fieldBlock, fieldIndex), MICROSECONDS_PER_MILLISECOND, UNNECESSARY);
                if (floorDiv(epochMillis, MILLISECONDS_PER_DAY) >= START_OF_MODERN_ERA_EPOCH_DAY) {
                    values.put(name, packDateTimeWithZone(epochMillis, UTC_KEY));
                }
                continue;
            }
            values.put(name, readNativeValue(type, fieldBlock, fieldIndex));
        }
        return values.buildOrThrow();
    }

    private Map<String, Object> parseNullCount(SqlRow row, List<DeltaLakeColumnMetadata> columns)
    {
        if (row == null) {
            // Statistics were not collected
            return ImmutableMap.of();
        }

        ImmutableMap.Builder<String, Object> values = ImmutableMap.builder();
        for (int i = 0; i < columns.size(); i++) {
            DeltaLakeColumnMetadata metadata = columns.get(i);

            ValueBlock fieldBlock = row.getUnderlyingFieldBlock(i);
            int fieldIndex = row.getUnderlyingFieldPosition(i);
            if (fieldBlock.isNull(fieldIndex)) {
                continue;
            }
            if (metadata.type() instanceof RowType) {
                if (checkpointRowStatisticsWritingEnabled) {
                    // RowType column statistics are not used for query planning, but need to be copied when writing out new Checkpoint files.
                    values.put(metadata.physicalName(), getRow(fieldBlock, fieldIndex));
                }
                continue;
            }

            values.put(metadata.physicalName(), getLongField(row, i));
        }
        return values.buildOrThrow();
    }

    private DeltaLakeTransactionLogEntry buildTxnEntry(ConnectorSession session, int pagePosition, Block block)
    {
        log.debug("Building txn entry from %s pagePosition %d", block, pagePosition);
        if (block.isNull(pagePosition)) {
            return null;
        }
        RowType type = txnType.orElseThrow();
        int txnFields = 3;
        SqlRow txnEntryRow = getRow(block, pagePosition);
        log.debug("Block %s has %s fields", block, txnEntryRow.getFieldCount());
        if (txnEntryRow.getFieldCount() != txnFields) {
            throw new TrinoException(DELTA_LAKE_INVALID_SCHEMA,
                    format("Expected block %s to have %d children, but found %s", block, txnFields, txnEntryRow.getFieldCount()));
        }
        CheckpointFieldReader txn = new CheckpointFieldReader(txnEntryRow, type);
        TransactionEntry result = new TransactionEntry(
                txn.getString("appId"),
                txn.getLong("version"),
                txn.getLong("lastUpdated"));
        log.debug("Result: %s", result);
        return DeltaLakeTransactionLogEntry.transactionEntry(result);
    }

    private static long getLongField(SqlRow row, int field)
    {
        LongArrayBlock valueBlock = (LongArrayBlock) row.getUnderlyingFieldBlock(field);
        return valueBlock.getLong(row.getUnderlyingFieldPosition(field));
    }

    @Override
    protected DeltaLakeTransactionLogEntry computeNext()
    {
        try {
            if (nextEntries.isEmpty()) {
                fillNextEntries();
            }
            if (!nextEntries.isEmpty()) {
                return nextEntries.remove();
            }
            pageSource.close();
            return endOfData();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean tryAdvancePage()
            throws IOException
    {
        if (pageSource.isFinished()) {
            pageSource.close();
            return false;
        }
        boolean isFirstPage = page == null;
        page = pageSource.getNextSourcePage();
        if (page == null) {
            return false;
        }
        if (isFirstPage) {
            int requiredExtractorChannels = extractors.size();
            if (page.getChannelCount() != requiredExtractorChannels) {
                throw new TrinoException(DELTA_LAKE_INVALID_SCHEMA,
                        format("Expected page in %s to contain %d channels, but found %d",
                                checkpointPath, requiredExtractorChannels, page.getChannelCount()));
            }
        }
        pagePosition = 0;
        return true;
    }

    public void close()
    {
        try {
            pageSource.close();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void fillNextEntries()
            throws IOException
    {
        while (nextEntries.isEmpty()) {
            // grab next page if needed
            while (page == null || pagePosition == page.getPositionCount()) {
                if (!tryAdvancePage()) {
                    return;
                }
            }

            // process page
            int blockIndex = 0;
            for (CheckpointFieldExtractor extractor : extractors) {
                DeltaLakeTransactionLogEntry entry = extractor.getEntry(session, pagePosition, page.getBlock(blockIndex));
                if (entry != null) {
                    nextEntries.add(entry);
                }
                blockIndex++;
            }
            pagePosition++;
        }
    }

    @VisibleForTesting
    OptionalLong getCompletedPositions()
    {
        return pageSource.getCompletedPositions();
    }

    @VisibleForTesting
    long getCompletedBytes()
    {
        return pageSource.getCompletedBytes();
    }

    @FunctionalInterface
    private interface CheckpointFieldExtractor
    {
        /**
         * Returns the transaction log entry instance corresponding to the requested position in the memory block.
         * The output of the operation may be `null` in case the block has no information at the requested position
         * or if the during the retrieval process it is observed that the log entry does not correspond to the
         * checkpoint filter criteria.
         */
        @Nullable
        DeltaLakeTransactionLogEntry getEntry(ConnectorSession session, int pagePosition, Block block);
    }

    private static SqlRow getRow(Block block, int position)
    {
        return ((RowBlock) block.getUnderlyingValueBlock()).getRow(block.getUnderlyingValuePosition(position));
    }
}

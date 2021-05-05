/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.config;

import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.client.bootstrap.BootstrapMode;
import org.apache.hudi.client.transaction.ConflictResolutionStrategy;
import org.apache.hudi.common.config.ConfigOption;
import org.apache.hudi.common.config.DefaultHoodieConfig;
import org.apache.hudi.common.config.HoodieMetadataConfig;
import org.apache.hudi.common.config.LockConfiguration;
import org.apache.hudi.common.engine.EngineType;
import org.apache.hudi.common.fs.ConsistencyGuardConfig;
import org.apache.hudi.common.model.HoodieFailedWritesCleaningPolicy;
import org.apache.hudi.common.model.HoodieCleaningPolicy;
import org.apache.hudi.common.model.OverwriteWithLatestAvroPayload;
import org.apache.hudi.common.model.WriteConcurrencyMode;
import org.apache.hudi.common.table.timeline.versioning.TimelineLayoutVersion;
import org.apache.hudi.common.table.view.FileSystemViewStorageConfig;
import org.apache.hudi.common.util.ReflectionUtils;
import org.apache.hudi.common.util.ValidationUtils;
import org.apache.hudi.execution.bulkinsert.BulkInsertSortMode;
import org.apache.hudi.index.HoodieIndex;
import org.apache.hudi.keygen.SimpleAvroKeyGenerator;
import org.apache.hudi.metrics.MetricsReporterType;
import org.apache.hudi.metrics.datadog.DatadogHttpClient.ApiSite;
import org.apache.hudi.table.action.compact.CompactionTriggerStrategy;
import org.apache.hudi.table.action.compact.strategy.CompactionStrategy;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import javax.annotation.concurrent.Immutable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.apache.hudi.common.config.LockConfiguration.HIVE_DATABASE_NAME_PROP;
import static org.apache.hudi.common.config.LockConfiguration.HIVE_TABLE_NAME_PROP;

/**
 * Class storing configs for the HoodieWriteClient.
 */
@Immutable
public class HoodieWriteConfig extends DefaultHoodieConfig {

  private static final long serialVersionUID = 0L;

  public static final ConfigOption<String> TABLE_NAME = ConfigOption
      .key("hoodie.table.name")
      .noDefaultValue()
      .withDescription("Table name that will be used for registering with Hive. Needs to be same across runs.");

  public static final ConfigOption<String> PRECOMBINE_FIELD_PROP = ConfigOption
      .key("hoodie.datasource.write.precombine.field")
      .defaultValue("ts")
      .withDescription("Field used in preCombining before actual write. When two records have the same key value, "
          + "we will pick the one with the largest value for the precombine field, determined by Object.compareTo(..)");

  public static final ConfigOption<String> WRITE_PAYLOAD_CLASS = ConfigOption
      .key("hoodie.datasource.write.payload.class")
      .defaultValue(OverwriteWithLatestAvroPayload.class.getName())
      .withDescription("Payload class used. Override this, if you like to roll your own merge logic, when upserting/inserting. "
          + "This will render any value set for PRECOMBINE_FIELD_OPT_VAL in-effective");

  public static final ConfigOption<String> KEYGENERATOR_CLASS_PROP = ConfigOption
      .key("hoodie.datasource.write.keygenerator.class")
      .defaultValue(SimpleAvroKeyGenerator.class.getName())
      .withDescription("Key generator class, that implements will extract the key out of incoming Row object");

  public static final ConfigOption<String> ROLLBACK_USING_MARKERS = ConfigOption
      .key("hoodie.rollback.using.markers")
      .defaultValue("false")
      .withDescription("Enables a more efficient mechanism for rollbacks based on the marker files generated "
          + "during the writes. Turned off by default.");

  public static final ConfigOption<String> TIMELINE_LAYOUT_VERSION = ConfigOption
      .key("hoodie.timeline.layout.version")
      .noDefaultValue()
      .withDescription("");

  public static final ConfigOption<String> BASE_PATH_PROP = ConfigOption
      .key("hoodie.base.path")
      .noDefaultValue()
      .withDescription("Base DFS path under which all the data partitions are created. "
          + "Always prefix it explicitly with the storage scheme (e.g hdfs://, s3:// etc). "
          + "Hudi stores all the main meta-data about commits, savepoints, cleaning audit logs "
          + "etc in .hoodie directory under the base directory.");

  public static final ConfigOption<String> AVRO_SCHEMA = ConfigOption
      .key("hoodie.avro.schema")
      .noDefaultValue()
      .withDescription("This is the current reader avro schema for the table. This is a string of the entire schema. "
          + "HoodieWriteClient uses this schema to pass on to implementations of HoodieRecordPayload to convert "
          + "from the source format to avro record. This is also used when re-writing records during an update.");

  public static final ConfigOption<String> AVRO_SCHEMA_VALIDATE = ConfigOption
      .key("hoodie.avro.schema.validate")
      .defaultValue("false")
      .withDescription("");

  public static final ConfigOption<String> INSERT_PARALLELISM = ConfigOption
      .key("hoodie.insert.shuffle.parallelism")
      .defaultValue("1500")
      .withDescription("Once data has been initially imported, this parallelism controls initial parallelism for reading input records. "
          + "Ensure this value is high enough say: 1 partition for 1 GB of input data");

  public static final ConfigOption<String> BULKINSERT_PARALLELISM = ConfigOption
      .key("hoodie.bulkinsert.shuffle.parallelism")
      .defaultValue("1500")
      .withDescription("Bulk insert is meant to be used for large initial imports and this parallelism determines "
          + "the initial number of files in your table. Tune this to achieve a desired optimal size during initial import.");

  public static final ConfigOption<String> BULKINSERT_USER_DEFINED_PARTITIONER_CLASS = ConfigOption
      .key("hoodie.bulkinsert.user.defined.partitioner.class")
      .noDefaultValue()
      .withDescription("If specified, this class will be used to re-partition input records before they are inserted.");

  public static final ConfigOption<String> BULKINSERT_INPUT_DATA_SCHEMA_DDL = ConfigOption
      .key("hoodie.bulkinsert.schema.ddl")
      .noDefaultValue()
      .withDescription("");

  public static final ConfigOption<String> UPSERT_PARALLELISM = ConfigOption
      .key("hoodie.upsert.shuffle.parallelism")
      .defaultValue("1500")
      .withDescription("Once data has been initially imported, this parallelism controls initial parallelism for reading input records. "
          + "Ensure this value is high enough say: 1 partition for 1 GB of input data");

  public static final ConfigOption<String> DELETE_PARALLELISM = ConfigOption
      .key("hoodie.delete.shuffle.parallelism")
      .defaultValue("1500")
      .withDescription("This parallelism is Used for “delete” operation while deduping or repartioning.");

  public static final ConfigOption<String> ROLLBACK_PARALLELISM = ConfigOption
      .key("hoodie.rollback.parallelism")
      .defaultValue("100")
      .withDescription("Determines the parallelism for rollback of commits.");

  public static final ConfigOption<String> WRITE_BUFFER_LIMIT_BYTES = ConfigOption
      .key("hoodie.write.buffer.limit.bytes")
      .defaultValue(String.valueOf(4 * 1024 * 1024))
      .withDescription("");

  public static final ConfigOption<String> COMBINE_BEFORE_INSERT_PROP = ConfigOption
      .key("hoodie.combine.before.insert")
      .defaultValue("false")
      .withDescription("Flag which first combines the input RDD and merges multiple partial records into a single record "
          + "before inserting or updating in DFS");

  public static final ConfigOption<String> COMBINE_BEFORE_UPSERT_PROP = ConfigOption
      .key("hoodie.combine.before.upsert")
      .defaultValue("true")
      .withDescription("Flag which first combines the input RDD and merges multiple partial records into a single record "
          + "before inserting or updating in DFS");

  public static final ConfigOption<String> COMBINE_BEFORE_DELETE_PROP = ConfigOption
      .key("hoodie.combine.before.delete")
      .defaultValue("true")
      .withDescription("Flag which first combines the input RDD and merges multiple partial records into a single record "
          + "before deleting in DFS");

  public static final ConfigOption<String> WRITE_STATUS_STORAGE_LEVEL = ConfigOption
      .key("hoodie.write.status.storage.level")
      .defaultValue("MEMORY_AND_DISK_SER")
      .withDescription("HoodieWriteClient.insert and HoodieWriteClient.upsert returns a persisted RDD[WriteStatus], "
          + "this is because the Client can choose to inspect the WriteStatus and choose and commit or not based on the failures. "
          + "This is a configuration for the storage level for this RDD");

  public static final ConfigOption<String> HOODIE_AUTO_COMMIT_PROP = ConfigOption
      .key("hoodie.auto.commit")
      .defaultValue("true")
      .withDescription("Should HoodieWriteClient autoCommit after insert and upsert. "
          + "The client can choose to turn off auto-commit and commit on a “defined success condition”");

  public static final ConfigOption<String> HOODIE_WRITE_STATUS_CLASS_PROP = ConfigOption
      .key("hoodie.writestatus.class")
      .defaultValue(WriteStatus.class.getName())
      .withDescription("");

  public static final ConfigOption<String> FINALIZE_WRITE_PARALLELISM = ConfigOption
      .key("hoodie.finalize.write.parallelism")
      .defaultValue("1500")
      .withDescription("");

  public static final ConfigOption<String> MARKERS_DELETE_PARALLELISM = ConfigOption
      .key("hoodie.markers.delete.parallelism")
      .defaultValue("100")
      .withDescription("Determines the parallelism for deleting marker files.");

  public static final ConfigOption<String> BULKINSERT_SORT_MODE = ConfigOption
      .key("hoodie.bulkinsert.sort.mode")
      .defaultValue(BulkInsertSortMode.GLOBAL_SORT.toString())
      .withDescription("Sorting modes to use for sorting records for bulk insert. This is leveraged when user "
          + "defined partitioner is not configured. Default is GLOBAL_SORT. Available values are - GLOBAL_SORT: "
          + "this ensures best file sizes, with lowest memory overhead at cost of sorting. PARTITION_SORT: "
          + "Strikes a balance by only sorting within a partition, still keeping the memory overhead of writing "
          + "lowest and best effort file sizing. NONE: No sorting. Fastest and matches spark.write.parquet() "
          + "in terms of number of files, overheads");

  public static final ConfigOption<String> EMBEDDED_TIMELINE_SERVER_ENABLED = ConfigOption
      .key("hoodie.embed.timeline.server")
      .defaultValue("true")
      .withDescription("");

  public static final ConfigOption<String> EMBEDDED_TIMELINE_SERVER_PORT = ConfigOption
      .key("hoodie.embed.timeline.server.port")
      .defaultValue("0")
      .withDescription("");

  public static final ConfigOption<String> EMBEDDED_TIMELINE_SERVER_THREADS = ConfigOption
      .key("hoodie.embed.timeline.server.threads")
      .defaultValue("-1")
      .withDescription("");

  public static final ConfigOption<String> EMBEDDED_TIMELINE_SERVER_COMPRESS_OUTPUT = ConfigOption
      .key("hoodie.embed.timeline.server.gzip")
      .defaultValue("true")
      .withDescription("");

  public static final ConfigOption<String> EMBEDDED_TIMELINE_SERVER_USE_ASYNC = ConfigOption
      .key("hoodie.embed.timeline.server.async")
      .defaultValue("false")
      .withDescription("");

  public static final ConfigOption<String> FAIL_ON_TIMELINE_ARCHIVING_ENABLED_PROP = ConfigOption
      .key("hoodie.fail.on.timeline.archiving")
      .defaultValue("true")
      .withDescription("");

  // time between successive attempts to ensure written data's metadata is consistent on storage
  public static final ConfigOption<Long> INITIAL_CONSISTENCY_CHECK_INTERVAL_MS_PROP = ConfigOption
      .key("hoodie.consistency.check.initial_interval_ms")
      .defaultValue(2000L)
      .withDescription("");

  // max interval time
  public static final ConfigOption<Long> MAX_CONSISTENCY_CHECK_INTERVAL_MS_PROP = ConfigOption
      .key("hoodie.consistency.check.max_interval_ms")
      .defaultValue(300000L)
      .withDescription("");

  // maximum number of checks, for consistency of written data. Will wait upto 256 Secs
  public static final ConfigOption<Integer> MAX_CONSISTENCY_CHECKS_PROP = ConfigOption
      .key("hoodie.consistency.check.max_checks")
      .defaultValue(7)
      .withDescription("");

  // Data validation check performed during merges before actual commits
  public static final ConfigOption<String> MERGE_DATA_VALIDATION_CHECK_ENABLED = ConfigOption
      .key("hoodie.merge.data.validation.enabled")
      .defaultValue("false")
      .withDescription("");

  // Allow duplicates with inserts while merging with existing records
  public static final ConfigOption<String> MERGE_ALLOW_DUPLICATE_ON_INSERTS = ConfigOption
      .key("hoodie.merge.allow.duplicate.on.inserts")
      .defaultValue("false")
      .withDescription("");

  public static final ConfigOption<Integer> CLIENT_HEARTBEAT_INTERVAL_IN_MS_PROP = ConfigOption
      .key("hoodie.client.heartbeat.interval_in_ms")
      .defaultValue(60 * 1000)
      .withDescription("");

  public static final ConfigOption<Integer> CLIENT_HEARTBEAT_NUM_TOLERABLE_MISSES_PROP = ConfigOption
      .key("hoodie.client.heartbeat.tolerable.misses")
      .defaultValue(2)
      .withDescription("");

  // Enable different concurrency support
  public static final ConfigOption<String> WRITE_CONCURRENCY_MODE_PROP = ConfigOption
      .key("hoodie.write.concurrency.mode")
      .defaultValue(WriteConcurrencyMode.SINGLE_WRITER.name())
      .withDescription("");

  // Comma separated metadata key prefixes to override from latest commit during overlapping commits via multi writing
  public static final ConfigOption<String> WRITE_META_KEY_PREFIXES_PROP = ConfigOption
      .key("hoodie.write.meta.key.prefixes")
      .defaultValue("")
      .withDescription("");

  /**
   * HUDI-858 : There are users who had been directly using RDD APIs and have relied on a behavior in 0.4.x to allow
   * multiple write operations (upsert/buk-insert/...) to be executed within a single commit.
   * <p>
   * Given Hudi commit protocol, these are generally unsafe operations and user need to handle failure scenarios. It
   * only works with COW table. Hudi 0.5.x had stopped this behavior.
   * <p>
   * Given the importance of supporting such cases for the user's migration to 0.5.x, we are proposing a safety flag
   * (disabled by default) which will allow this old behavior.
   */
  public static final ConfigOption<String> ALLOW_MULTI_WRITE_ON_SAME_INSTANT = ConfigOption
      .key("_.hoodie.allow.multi.write.on.same.instant")
      .defaultValue("false")
      .withDescription("");

  public static final ConfigOption<String> EXTERNAL_RECORD_AND_SCHEMA_TRANSFORMATION = ConfigOption
      .key(AVRO_SCHEMA + ".externalTransformation")
      .defaultValue("false")
      .withDescription("");

  private ConsistencyGuardConfig consistencyGuardConfig;

  // Hoodie Write Client transparently rewrites File System View config when embedded mode is enabled
  // We keep track of original config and rewritten config
  private final FileSystemViewStorageConfig clientSpecifiedViewStorageConfig;
  private FileSystemViewStorageConfig viewStorageConfig;
  private HoodiePayloadConfig hoodiePayloadConfig;
  private HoodieMetadataConfig metadataConfig;

  private EngineType engineType;

  /**
   * Use Spark engine by default.
   */
  protected HoodieWriteConfig(Properties props) {
    this(EngineType.SPARK, props);
  }

  protected HoodieWriteConfig(EngineType engineType, Properties props) {
    super(props);
    Properties newProps = new Properties();
    newProps.putAll(props);
    this.engineType = engineType;
    this.consistencyGuardConfig = ConsistencyGuardConfig.newBuilder().fromProperties(newProps).build();
    this.clientSpecifiedViewStorageConfig = FileSystemViewStorageConfig.newBuilder().fromProperties(newProps).build();
    this.viewStorageConfig = clientSpecifiedViewStorageConfig;
    this.hoodiePayloadConfig = HoodiePayloadConfig.newBuilder().fromProperties(newProps).build();
    this.metadataConfig = HoodieMetadataConfig.newBuilder().fromProperties(props).build();
  }

  public static HoodieWriteConfig.Builder newBuilder() {
    return new Builder();
  }

  /**
   * base properties.
   */
  public String getBasePath() {
    return getString(props, BASE_PATH_PROP);
  }

  public String getSchema() {
    return getString(props, AVRO_SCHEMA);
  }

  public void setSchema(String schemaStr) {
    set(props, AVRO_SCHEMA, schemaStr);
  }

  public boolean getAvroSchemaValidate() {
    return getBoolean(props, AVRO_SCHEMA_VALIDATE);
  }

  public String getTableName() {
    return getString(props, TABLE_NAME);
  }

  public String getPreCombineField() {
    return getString(props, PRECOMBINE_FIELD_PROP);
  }

  public String getWritePayloadClass() {
    return getString(props, WRITE_PAYLOAD_CLASS);
  }

  public String getKeyGeneratorClass() {
    return getString(props, KEYGENERATOR_CLASS_PROP);
  }

  public Boolean shouldAutoCommit() {
    return getBoolean(props, HOODIE_AUTO_COMMIT_PROP);
  }

  public Boolean shouldAssumeDatePartitioning() {
    return metadataConfig.shouldAssumeDatePartitioning();
  }

  public boolean shouldUseExternalSchemaTransformation() {
    return getBoolean(props, EXTERNAL_RECORD_AND_SCHEMA_TRANSFORMATION);
  }

  public Integer getTimelineLayoutVersion() {
    return getInt(props, TIMELINE_LAYOUT_VERSION);
  }

  public int getBulkInsertShuffleParallelism() {
    return getInt(props, BULKINSERT_PARALLELISM);
  }

  public String getUserDefinedBulkInsertPartitionerClass() {
    return getString(props, BULKINSERT_USER_DEFINED_PARTITIONER_CLASS);
  }

  public int getInsertShuffleParallelism() {
    return getInt(props, INSERT_PARALLELISM);
  }

  public int getUpsertShuffleParallelism() {
    return getInt(props, UPSERT_PARALLELISM);
  }

  public int getDeleteShuffleParallelism() {
    return Math.max(getInt(props, DELETE_PARALLELISM), 1);
  }

  public int getRollbackParallelism() {
    return getInt(props, ROLLBACK_PARALLELISM);
  }

  public int getFileListingParallelism() {
    return metadataConfig.getFileListingParallelism();
  }

  public boolean shouldRollbackUsingMarkers() {
    return getBoolean(props, ROLLBACK_USING_MARKERS);
  }

  public int getWriteBufferLimitBytes() {
    return Integer.parseInt(getStringOrDefault(props, WRITE_BUFFER_LIMIT_BYTES));
  }

  public boolean shouldCombineBeforeInsert() {
    return getBoolean(props, COMBINE_BEFORE_INSERT_PROP);
  }

  public boolean shouldCombineBeforeUpsert() {
    return getBoolean(props, COMBINE_BEFORE_UPSERT_PROP);
  }

  public boolean shouldCombineBeforeDelete() {
    return getBoolean(props, COMBINE_BEFORE_DELETE_PROP);
  }

  public boolean shouldAllowMultiWriteOnSameInstant() {
    return getBoolean(props, ALLOW_MULTI_WRITE_ON_SAME_INSTANT);
  }

  public String getWriteStatusClassName() {
    return getString(props, HOODIE_WRITE_STATUS_CLASS_PROP);
  }

  public int getFinalizeWriteParallelism() {
    return getInt(props, FINALIZE_WRITE_PARALLELISM);
  }

  public int getMarkersDeleteParallelism() {
    return getInt(props, MARKERS_DELETE_PARALLELISM);
  }

  public boolean isEmbeddedTimelineServerEnabled() {
    return getBoolean(props, EMBEDDED_TIMELINE_SERVER_ENABLED);
  }

  public int getEmbeddedTimelineServerPort() {
    return Integer.parseInt(getStringOrDefault(props, EMBEDDED_TIMELINE_SERVER_PORT));
  }

  public int getEmbeddedTimelineServerThreads() {
    return Integer.parseInt(getStringOrDefault(props, EMBEDDED_TIMELINE_SERVER_THREADS));
  }

  public boolean getEmbeddedTimelineServerCompressOutput() {
    return Boolean.parseBoolean(getStringOrDefault(props, EMBEDDED_TIMELINE_SERVER_COMPRESS_OUTPUT));
  }

  public boolean getEmbeddedTimelineServerUseAsync() {
    return Boolean.parseBoolean(getStringOrDefault(props, EMBEDDED_TIMELINE_SERVER_USE_ASYNC));
  }

  public boolean isFailOnTimelineArchivingEnabled() {
    return getBoolean(props, FAIL_ON_TIMELINE_ARCHIVING_ENABLED_PROP);
  }

  public int getMaxConsistencyChecks() {
    return getInt(props, MAX_CONSISTENCY_CHECKS_PROP);
  }

  public int getInitialConsistencyCheckIntervalMs() {
    return getInt(props, INITIAL_CONSISTENCY_CHECK_INTERVAL_MS_PROP);
  }

  public int getMaxConsistencyCheckIntervalMs() {
    return getInt(props, MAX_CONSISTENCY_CHECK_INTERVAL_MS_PROP);
  }

  public BulkInsertSortMode getBulkInsertSortMode() {
    String sortMode = getString(props, BULKINSERT_SORT_MODE);
    return BulkInsertSortMode.valueOf(sortMode.toUpperCase());
  }

  public boolean isMergeDataValidationCheckEnabled() {
    return getBoolean(props, MERGE_DATA_VALIDATION_CHECK_ENABLED);
  }

  public boolean allowDuplicateInserts() {
    return getBoolean(props, MERGE_ALLOW_DUPLICATE_ON_INSERTS);
  }

  public EngineType getEngineType() {
    return engineType;
  }

  /**
   * compaction properties.
   */
  public HoodieCleaningPolicy getCleanerPolicy() {
    return HoodieCleaningPolicy.valueOf(getString(props, HoodieCompactionConfig.CLEANER_POLICY_PROP));
  }

  public int getCleanerFileVersionsRetained() {
    return getInt(props, HoodieCompactionConfig.CLEANER_FILE_VERSIONS_RETAINED_PROP);
  }

  public int getCleanerCommitsRetained() {
    return getInt(props, HoodieCompactionConfig.CLEANER_COMMITS_RETAINED_PROP);
  }

  public int getMaxCommitsToKeep() {
    return getInt(props, HoodieCompactionConfig.MAX_COMMITS_TO_KEEP_PROP);
  }

  public int getMinCommitsToKeep() {
    return getInt(props, HoodieCompactionConfig.MIN_COMMITS_TO_KEEP_PROP);
  }

  public int getParquetSmallFileLimit() {
    return getInt(props, HoodieCompactionConfig.PARQUET_SMALL_FILE_LIMIT_BYTES);
  }

  public double getRecordSizeEstimationThreshold() {
    return getDouble(props, HoodieCompactionConfig.RECORD_SIZE_ESTIMATION_THRESHOLD_PROP);
  }

  public int getCopyOnWriteInsertSplitSize() {
    return getInt(props, HoodieCompactionConfig.COPY_ON_WRITE_TABLE_INSERT_SPLIT_SIZE);
  }

  public int getCopyOnWriteRecordSizeEstimate() {
    return getInt(props, HoodieCompactionConfig.COPY_ON_WRITE_TABLE_RECORD_SIZE_ESTIMATE);
  }

  public boolean shouldAutoTuneInsertSplits() {
    return getBoolean(props, HoodieCompactionConfig.COPY_ON_WRITE_TABLE_AUTO_SPLIT_INSERTS);
  }

  public int getCleanerParallelism() {
    return getInt(props, HoodieCompactionConfig.CLEANER_PARALLELISM);
  }

  public boolean isAutoClean() {
    return getBoolean(props, HoodieCompactionConfig.AUTO_CLEAN_PROP);
  }

  public boolean isAsyncClean() {
    return getBoolean(props, HoodieCompactionConfig.ASYNC_CLEAN_PROP);
  }

  public boolean incrementalCleanerModeEnabled() {
    return getBoolean(props, HoodieCompactionConfig.CLEANER_INCREMENTAL_MODE);
  }

  public boolean inlineCompactionEnabled() {
    return getBoolean(props, HoodieCompactionConfig.INLINE_COMPACT_PROP);
  }

  public CompactionTriggerStrategy getInlineCompactTriggerStrategy() {
    return CompactionTriggerStrategy.valueOf(getString(props, HoodieCompactionConfig.INLINE_COMPACT_TRIGGER_STRATEGY_PROP));
  }

  public int getInlineCompactDeltaCommitMax() {
    return getInt(props, HoodieCompactionConfig.INLINE_COMPACT_NUM_DELTA_COMMITS_PROP);
  }

  public int getInlineCompactDeltaSecondsMax() {
    return getInt(props, HoodieCompactionConfig.INLINE_COMPACT_TIME_DELTA_SECONDS_PROP);
  }

  public CompactionStrategy getCompactionStrategy() {
    return ReflectionUtils.loadClass(getString(props, HoodieCompactionConfig.COMPACTION_STRATEGY_PROP));
  }

  public Long getTargetIOPerCompactionInMB() {
    return getLong(props, HoodieCompactionConfig.TARGET_IO_PER_COMPACTION_IN_MB_PROP);
  }

  public Boolean getCompactionLazyBlockReadEnabled() {
    return getBoolean(props, HoodieCompactionConfig.COMPACTION_LAZY_BLOCK_READ_ENABLED_PROP);
  }

  public Boolean getCompactionReverseLogReadEnabled() {
    return getBoolean(props, HoodieCompactionConfig.COMPACTION_REVERSE_LOG_READ_ENABLED_PROP);
  }

  public boolean inlineClusteringEnabled() {
    return getBoolean(props, HoodieClusteringConfig.INLINE_CLUSTERING_PROP);
  }

  public boolean isAsyncClusteringEnabled() {
    return getBoolean(props, HoodieClusteringConfig.ASYNC_CLUSTERING_ENABLE_OPT_KEY);
  }

  public boolean isClusteringEnabled() {
    // TODO: future support async clustering
    return inlineClusteringEnabled() || isAsyncClusteringEnabled();
  }

  public int getInlineClusterMaxCommits() {
    return getInt(props, HoodieClusteringConfig.INLINE_CLUSTERING_MAX_COMMIT_PROP);
  }

  public String getPayloadClass() {
    return getString(props, HoodieCompactionConfig.PAYLOAD_CLASS_PROP);
  }

  public int getTargetPartitionsPerDayBasedCompaction() {
    return getInt(props, HoodieCompactionConfig.TARGET_PARTITIONS_PER_DAYBASED_COMPACTION_PROP);
  }

  public int getCommitArchivalBatchSize() {
    return getInt(props, HoodieCompactionConfig.COMMITS_ARCHIVAL_BATCH_SIZE_PROP);
  }

  public Boolean shouldCleanBootstrapBaseFile() {
    return getBoolean(props, HoodieCompactionConfig.CLEANER_BOOTSTRAP_BASE_FILE_ENABLED);
  }

  public String getClusteringUpdatesStrategyClass() {
    return getString(props, HoodieClusteringConfig.CLUSTERING_UPDATES_STRATEGY_PROP);
  }

  public HoodieFailedWritesCleaningPolicy getFailedWritesCleanPolicy() {
    return HoodieFailedWritesCleaningPolicy
        .valueOf(getString(props, HoodieCompactionConfig.FAILED_WRITES_CLEANER_POLICY_PROP));
  }

  /**
   * Clustering properties.
   */
  public String getClusteringPlanStrategyClass() {
    return getString(props, HoodieClusteringConfig.CLUSTERING_PLAN_STRATEGY_CLASS);
  }

  public String getClusteringExecutionStrategyClass() {
    return getString(props, HoodieClusteringConfig.CLUSTERING_EXECUTION_STRATEGY_CLASS);
  }

  public long getClusteringMaxBytesInGroup() {
    return getLong(props, HoodieClusteringConfig.CLUSTERING_MAX_BYTES_PER_GROUP);
  }

  public long getClusteringSmallFileLimit() {
    return getLong(props, HoodieClusteringConfig.CLUSTERING_PLAN_SMALL_FILE_LIMIT);
  }

  public int getClusteringMaxNumGroups() {
    return getInt(props, HoodieClusteringConfig.CLUSTERING_MAX_NUM_GROUPS);
  }

  public long getClusteringTargetFileMaxBytes() {
    return getLong(props, HoodieClusteringConfig.CLUSTERING_TARGET_FILE_MAX_BYTES);
  }

  public int getTargetPartitionsForClustering() {
    return getInt(props, HoodieClusteringConfig.CLUSTERING_TARGET_PARTITIONS);
  }

  public String getClusteringSortColumns() {
    return getString(props, HoodieClusteringConfig.CLUSTERING_SORT_COLUMNS_PROPERTY);
  }

  /**
   * index properties.
   */
  public HoodieIndex.IndexType getIndexType() {
    return HoodieIndex.IndexType.valueOf(getString(props, HoodieIndexConfig.INDEX_TYPE_PROP));
  }

  public String getIndexClass() {
    return getString(props, HoodieIndexConfig.INDEX_CLASS_PROP);
  }

  public int getBloomFilterNumEntries() {
    return getInt(props, HoodieIndexConfig.BLOOM_FILTER_NUM_ENTRIES);
  }

  public double getBloomFilterFPP() {
    return getDouble(props, HoodieIndexConfig.BLOOM_FILTER_FPP);
  }

  public String getHbaseZkQuorum() {
    return getString(props, HoodieHBaseIndexConfig.HBASE_ZKQUORUM_PROP);
  }

  public int getHbaseZkPort() {
    return getInt(props, HoodieHBaseIndexConfig.HBASE_ZKPORT_PROP);
  }

  public String getHBaseZkZnodeParent() {
    return getString(props, HoodieHBaseIndexConfig.HBASE_ZK_ZNODEPARENT);
  }

  public String getHbaseTableName() {
    return getString(props, HoodieHBaseIndexConfig.HBASE_TABLENAME_PROP);
  }

  public int getHbaseIndexGetBatchSize() {
    return getInt(props, HoodieHBaseIndexConfig.HBASE_GET_BATCH_SIZE_PROP);
  }

  public Boolean getHBaseIndexRollbackSync() {
    return getBoolean(props, HoodieHBaseIndexConfig.HBASE_INDEX_ROLLBACK_SYNC);
  }

  public int getHbaseIndexPutBatchSize() {
    return getInt(props, HoodieHBaseIndexConfig.HBASE_PUT_BATCH_SIZE_PROP);
  }

  public Boolean getHbaseIndexPutBatchSizeAutoCompute() {
    return getBoolean(props, HoodieHBaseIndexConfig.HBASE_PUT_BATCH_SIZE_AUTO_COMPUTE_PROP);
  }

  public String getHBaseQPSResourceAllocatorClass() {
    return getString(props, HoodieHBaseIndexConfig.HBASE_INDEX_QPS_ALLOCATOR_CLASS);
  }

  public String getHBaseQPSZKnodePath() {
    return getString(props, HoodieHBaseIndexConfig.HBASE_ZK_PATH_QPS_ROOT);
  }

  public String getHBaseZkZnodeSessionTimeout() {
    return getString(props, HoodieHBaseIndexConfig.HOODIE_INDEX_HBASE_ZK_SESSION_TIMEOUT_MS);
  }

  public String getHBaseZkZnodeConnectionTimeout() {
    return getString(props, HoodieHBaseIndexConfig.HOODIE_INDEX_HBASE_ZK_CONNECTION_TIMEOUT_MS);
  }

  public boolean getHBaseIndexShouldComputeQPSDynamically() {
    return getBoolean(props, HoodieHBaseIndexConfig.HOODIE_INDEX_COMPUTE_QPS_DYNAMICALLY);
  }

  public int getHBaseIndexDesiredPutsTime() {
    return getInt(props, HoodieHBaseIndexConfig.HOODIE_INDEX_DESIRED_PUTS_TIME_IN_SECS);
  }

  public String getBloomFilterType() {
    return getString(props, HoodieIndexConfig.BLOOM_INDEX_FILTER_TYPE);
  }

  public int getDynamicBloomFilterMaxNumEntries() {
    return getInt(props, HoodieIndexConfig.HOODIE_BLOOM_INDEX_FILTER_DYNAMIC_MAX_ENTRIES);
  }

  /**
   * Fraction of the global share of QPS that should be allocated to this job. Let's say there are 3 jobs which have
   * input size in terms of number of rows required for HbaseIndexing as x, 2x, 3x respectively. Then this fraction for
   * the jobs would be (0.17) 1/6, 0.33 (2/6) and 0.5 (3/6) respectively.
   */
  public float getHbaseIndexQPSFraction() {
    return getFloat(props, HoodieHBaseIndexConfig.HBASE_QPS_FRACTION_PROP);
  }

  public float getHBaseIndexMinQPSFraction() {
    return getFloat(props, HoodieHBaseIndexConfig.HBASE_MIN_QPS_FRACTION_PROP);
  }

  public float getHBaseIndexMaxQPSFraction() {
    return getFloat(props, HoodieHBaseIndexConfig.HBASE_MAX_QPS_FRACTION_PROP);
  }

  /**
   * This should be same across various jobs. This is intended to limit the aggregate QPS generated across various
   * Hoodie jobs to an Hbase Region Server
   */
  public int getHbaseIndexMaxQPSPerRegionServer() {
    return getInt(props, HoodieHBaseIndexConfig.HBASE_MAX_QPS_PER_REGION_SERVER_PROP);
  }

  public boolean getHbaseIndexUpdatePartitionPath() {
    return getBoolean(props, HoodieHBaseIndexConfig.HBASE_INDEX_UPDATE_PARTITION_PATH);
  }

  public int getBloomIndexParallelism() {
    return getInt(props, HoodieIndexConfig.BLOOM_INDEX_PARALLELISM_PROP);
  }

  public boolean getBloomIndexPruneByRanges() {
    return getBoolean(props, HoodieIndexConfig.BLOOM_INDEX_PRUNE_BY_RANGES_PROP);
  }

  public boolean getBloomIndexUseCaching() {
    return getBoolean(props, HoodieIndexConfig.BLOOM_INDEX_USE_CACHING_PROP);
  }

  public boolean useBloomIndexTreebasedFilter() {
    return getBoolean(props, HoodieIndexConfig.BLOOM_INDEX_TREE_BASED_FILTER_PROP);
  }

  public boolean useBloomIndexBucketizedChecking() {
    return getBoolean(props, HoodieIndexConfig.BLOOM_INDEX_BUCKETIZED_CHECKING_PROP);
  }

  public int getBloomIndexKeysPerBucket() {
    return getInt(props, HoodieIndexConfig.BLOOM_INDEX_KEYS_PER_BUCKET_PROP);
  }

  public boolean getBloomIndexUpdatePartitionPath() {
    return getBoolean(props, HoodieIndexConfig.BLOOM_INDEX_UPDATE_PARTITION_PATH);
  }

  public int getSimpleIndexParallelism() {
    return getInt(props, HoodieIndexConfig.SIMPLE_INDEX_PARALLELISM_PROP);
  }

  public boolean getSimpleIndexUseCaching() {
    return getBoolean(props, HoodieIndexConfig.SIMPLE_INDEX_USE_CACHING_PROP);
  }

  public int getGlobalSimpleIndexParallelism() {
    return getInt(props, HoodieIndexConfig.GLOBAL_SIMPLE_INDEX_PARALLELISM_PROP);
  }

  public boolean getGlobalSimpleIndexUpdatePartitionPath() {
    return getBoolean(props, HoodieIndexConfig.SIMPLE_INDEX_UPDATE_PARTITION_PATH);
  }

  /**
   * storage properties.
   */
  public long getParquetMaxFileSize() {
    return getLong(props, HoodieStorageConfig.PARQUET_FILE_MAX_BYTES);
  }

  public int getParquetBlockSize() {
    return getInt(props, HoodieStorageConfig.PARQUET_BLOCK_SIZE_BYTES);
  }

  public int getParquetPageSize() {
    return getInt(props, HoodieStorageConfig.PARQUET_PAGE_SIZE_BYTES);
  }

  public int getLogFileDataBlockMaxSize() {
    return getInt(props, HoodieStorageConfig.LOGFILE_DATA_BLOCK_SIZE_MAX_BYTES);
  }

  public int getLogFileMaxSize() {
    return getInt(props, HoodieStorageConfig.LOGFILE_SIZE_MAX_BYTES);
  }

  public double getParquetCompressionRatio() {
    return getDouble(props, HoodieStorageConfig.PARQUET_COMPRESSION_RATIO);
  }

  public CompressionCodecName getParquetCompressionCodec() {
    return CompressionCodecName.fromConf(getString(props, HoodieStorageConfig.PARQUET_COMPRESSION_CODEC));
  }

  public double getLogFileToParquetCompressionRatio() {
    return getDouble(props, HoodieStorageConfig.LOGFILE_TO_PARQUET_COMPRESSION_RATIO);
  }

  public long getHFileMaxFileSize() {
    return getLong(props, HoodieStorageConfig.HFILE_FILE_MAX_BYTES);
  }

  public int getHFileBlockSize() {
    return getInt(props, HoodieStorageConfig.HFILE_BLOCK_SIZE_BYTES);
  }

  public Compression.Algorithm getHFileCompressionAlgorithm() {
    return Compression.Algorithm.valueOf(getString(props, HoodieStorageConfig.HFILE_COMPRESSION_ALGORITHM));
  }

  /**
   * metrics properties.
   */
  public boolean isMetricsOn() {
    return getBoolean(props, HoodieMetricsConfig.METRICS_ON);
  }

  public boolean isExecutorMetricsEnabled() {
    return Boolean.parseBoolean(getStringOrElse(props, HoodieMetricsConfig.ENABLE_EXECUTOR_METRICS, "false"));
  }

  public MetricsReporterType getMetricsReporterType() {
    return MetricsReporterType.valueOf(getString(props, HoodieMetricsConfig.METRICS_REPORTER_TYPE));
  }

  public String getGraphiteServerHost() {
    return getString(props, HoodieMetricsConfig.GRAPHITE_SERVER_HOST);
  }

  public int getGraphiteServerPort() {
    return getInt(props, HoodieMetricsConfig.GRAPHITE_SERVER_PORT);
  }

  public String getGraphiteMetricPrefix() {
    return getString(props, HoodieMetricsConfig.GRAPHITE_METRIC_PREFIX);
  }

  public String getJmxHost() {
    return getString(props, HoodieMetricsConfig.JMX_HOST);
  }

  public String getJmxPort() {
    return getString(props, HoodieMetricsConfig.JMX_PORT);
  }

  public int getDatadogReportPeriodSeconds() {
    return getInt(props, HoodieMetricsDatadogConfig.DATADOG_REPORT_PERIOD_SECONDS);
  }

  public ApiSite getDatadogApiSite() {
    return ApiSite.valueOf(getString(props, HoodieMetricsDatadogConfig.DATADOG_API_SITE));
  }

  public String getDatadogApiKey() {
    if (props.containsKey(HoodieMetricsDatadogConfig.DATADOG_API_KEY.key())) {
      return getString(props, HoodieMetricsDatadogConfig.DATADOG_API_KEY);
    } else {
      Supplier<String> apiKeySupplier = ReflectionUtils.loadClass(
          getString(props, HoodieMetricsDatadogConfig.DATADOG_API_KEY_SUPPLIER));
      return apiKeySupplier.get();
    }
  }

  public boolean getDatadogApiKeySkipValidation() {
    return getBoolean(props, HoodieMetricsDatadogConfig.DATADOG_API_KEY_SKIP_VALIDATION);
  }

  public int getDatadogApiTimeoutSeconds() {
    return getInt(props, HoodieMetricsDatadogConfig.DATADOG_API_TIMEOUT_SECONDS);
  }

  public String getDatadogMetricPrefix() {
    return getString(props, HoodieMetricsDatadogConfig.DATADOG_METRIC_PREFIX);
  }

  public String getDatadogMetricHost() {
    return getString(props, HoodieMetricsDatadogConfig.DATADOG_METRIC_HOST);
  }

  public List<String> getDatadogMetricTags() {
    return Arrays.stream(getStringOrElse(props,
        HoodieMetricsDatadogConfig.DATADOG_METRIC_TAGS, ",").split("\\s*,\\s*")).collect(Collectors.toList());
  }

  public String getMetricReporterClassName() {
    return getString(props, HoodieMetricsConfig.METRICS_REPORTER_CLASS);
  }

  public int getPrometheusPort() {
    return getInt(props, HoodieMetricsPrometheusConfig.PROMETHEUS_PORT);
  }

  public String getPushGatewayHost() {
    return getString(props, HoodieMetricsPrometheusConfig.PUSHGATEWAY_HOST);
  }

  public int getPushGatewayPort() {
    return getInt(props, HoodieMetricsPrometheusConfig.PUSHGATEWAY_PORT);
  }

  public int getPushGatewayReportPeriodSeconds() {
    return getInt(props, HoodieMetricsPrometheusConfig.PUSHGATEWAY_REPORT_PERIOD_SECONDS);
  }

  public boolean getPushGatewayDeleteOnShutdown() {
    return getBoolean(props, HoodieMetricsPrometheusConfig.PUSHGATEWAY_DELETE_ON_SHUTDOWN);
  }

  public String getPushGatewayJobName() {
    return getString(props, HoodieMetricsPrometheusConfig.PUSHGATEWAY_JOB_NAME);
  }

  public boolean getPushGatewayRandomJobNameSuffix() {
    return getBoolean(props, HoodieMetricsPrometheusConfig.PUSHGATEWAY_RANDOM_JOB_NAME_SUFFIX);
  }

  /**
   * memory configs.
   */
  public int getMaxDFSStreamBufferSize() {
    return getInt(props, HoodieMemoryConfig.MAX_DFS_STREAM_BUFFER_SIZE_PROP);
  }

  public String getSpillableMapBasePath() {
    return getString(props, HoodieMemoryConfig.SPILLABLE_MAP_BASE_PATH_PROP);
  }

  public double getWriteStatusFailureFraction() {
    return getDouble(props, HoodieMemoryConfig.WRITESTATUS_FAILURE_FRACTION_PROP);
  }

  public ConsistencyGuardConfig getConsistencyGuardConfig() {
    return consistencyGuardConfig;
  }

  public void setConsistencyGuardConfig(ConsistencyGuardConfig consistencyGuardConfig) {
    this.consistencyGuardConfig = consistencyGuardConfig;
  }

  public FileSystemViewStorageConfig getViewStorageConfig() {
    return viewStorageConfig;
  }

  public void setViewStorageConfig(FileSystemViewStorageConfig viewStorageConfig) {
    this.viewStorageConfig = viewStorageConfig;
  }

  public void resetViewStorageConfig() {
    this.setViewStorageConfig(getClientSpecifiedViewStorageConfig());
  }

  public FileSystemViewStorageConfig getClientSpecifiedViewStorageConfig() {
    return clientSpecifiedViewStorageConfig;
  }

  public HoodiePayloadConfig getPayloadConfig() {
    return hoodiePayloadConfig;
  }

  public HoodieMetadataConfig getMetadataConfig() {
    return metadataConfig;
  }

  /**
   * Commit call back configs.
   */
  public boolean writeCommitCallbackOn() {
    return getBoolean(props, HoodieWriteCommitCallbackConfig.CALLBACK_ON);
  }

  public String getCallbackClass() {
    return getString(props, HoodieWriteCommitCallbackConfig.CALLBACK_CLASS_PROP);
  }

  public String getBootstrapSourceBasePath() {
    return getString(props, HoodieBootstrapConfig.BOOTSTRAP_BASE_PATH_PROP);
  }

  public String getBootstrapModeSelectorClass() {
    return getString(props, HoodieBootstrapConfig.BOOTSTRAP_MODE_SELECTOR);
  }

  public String getFullBootstrapInputProvider() {
    return getString(props, HoodieBootstrapConfig.FULL_BOOTSTRAP_INPUT_PROVIDER);
  }

  public String getBootstrapKeyGeneratorClass() {
    return getString(props, HoodieBootstrapConfig.BOOTSTRAP_KEYGEN_CLASS);
  }

  public String getBootstrapModeSelectorRegex() {
    return getString(props, HoodieBootstrapConfig.BOOTSTRAP_MODE_SELECTOR_REGEX);
  }

  public BootstrapMode getBootstrapModeForRegexMatch() {
    return BootstrapMode.valueOf(getString(props, HoodieBootstrapConfig.BOOTSTRAP_MODE_SELECTOR_REGEX_MODE));
  }

  public String getBootstrapPartitionPathTranslatorClass() {
    return getString(props, HoodieBootstrapConfig.BOOTSTRAP_PARTITION_PATH_TRANSLATOR_CLASS);
  }

  public int getBootstrapParallelism() {
    return getInt(props, HoodieBootstrapConfig.BOOTSTRAP_PARALLELISM);
  }

  public Long getMaxMemoryPerPartitionMerge() {
    return getLong(props, HoodieMemoryConfig.MAX_MEMORY_FOR_MERGE_PROP);
  }

  public Long getHoodieClientHeartbeatIntervalInMs() {
    return getLong(props, CLIENT_HEARTBEAT_INTERVAL_IN_MS_PROP);
  }

  public Integer getHoodieClientHeartbeatTolerableMisses() {
    return getInt(props, CLIENT_HEARTBEAT_NUM_TOLERABLE_MISSES_PROP);
  }

  /**
   * File listing metadata configs.
   */
  public boolean useFileListingMetadata() {
    return metadataConfig.useFileListingMetadata();
  }

  public boolean getFileListingMetadataVerify() {
    return metadataConfig.validateFileListingMetadata();
  }

  public int getMetadataInsertParallelism() {
    return getInt(props, HoodieMetadataConfig.METADATA_INSERT_PARALLELISM_PROP);
  }

  public int getMetadataCompactDeltaCommitMax() {
    return getInt(props, HoodieMetadataConfig.METADATA_COMPACT_NUM_DELTA_COMMITS_PROP);
  }

  public boolean isMetadataAsyncClean() {
    return getBoolean(props, HoodieMetadataConfig.METADATA_ASYNC_CLEAN_PROP);
  }

  public int getMetadataMaxCommitsToKeep() {
    return getInt(props, HoodieMetadataConfig.MAX_COMMITS_TO_KEEP_PROP);
  }

  public int getMetadataMinCommitsToKeep() {
    return getInt(props, HoodieMetadataConfig.MIN_COMMITS_TO_KEEP_PROP);
  }

  public int getMetadataCleanerCommitsRetained() {
    return getInt(props, HoodieMetadataConfig.CLEANER_COMMITS_RETAINED_PROP);
  }

  /**
   * Hoodie Client Lock Configs.
   * @return
   */

  public String getLockProviderClass() {
    return getString(props, HoodieLockConfig.LOCK_PROVIDER_CLASS_PROP);
  }

  public String getLockHiveDatabaseName() {
    return getString(props, HIVE_DATABASE_NAME_PROP);
  }

  public String getLockHiveTableName() {
    return getString(props, HIVE_TABLE_NAME_PROP);
  }

  public ConflictResolutionStrategy getWriteConflictResolutionStrategy() {
    return ReflectionUtils.loadClass(getString(props, HoodieLockConfig.WRITE_CONFLICT_RESOLUTION_STRATEGY_CLASS_PROP));
  }

  public Long getLockAcquireWaitTimeoutInMs() {
    return getLong(props, LockConfiguration.LOCK_ACQUIRE_WAIT_TIMEOUT_MS_PROP);
  }

  public WriteConcurrencyMode getWriteConcurrencyMode() {
    return WriteConcurrencyMode.fromValue(getString(props, WRITE_CONCURRENCY_MODE_PROP));
  }

  public Boolean inlineTableServices() {
    return inlineClusteringEnabled() || inlineCompactionEnabled() || isAutoClean();
  }

  public String getWriteMetaKeyPrefixes() {
    return getString(props, WRITE_META_KEY_PREFIXES_PROP);
  }

  public static class Builder {

    protected final Properties props = new Properties();
    protected EngineType engineType = EngineType.SPARK;
    private boolean isIndexConfigSet = false;
    private boolean isStorageConfigSet = false;
    private boolean isCompactionConfigSet = false;
    private boolean isClusteringConfigSet = false;
    private boolean isMetricsConfigSet = false;
    private boolean isBootstrapConfigSet = false;
    private boolean isMemoryConfigSet = false;
    private boolean isViewConfigSet = false;
    private boolean isConsistencyGuardSet = false;
    private boolean isCallbackConfigSet = false;
    private boolean isPayloadConfigSet = false;
    private boolean isMetadataConfigSet = false;
    private boolean isLockConfigSet = false;

    public Builder withEngineType(EngineType engineType) {
      this.engineType = engineType;
      return this;
    }

    public Builder fromFile(File propertiesFile) throws IOException {
      try (FileReader reader = new FileReader(propertiesFile)) {
        this.props.load(reader);
        return this;
      }
    }

    public Builder fromInputStream(InputStream inputStream) throws IOException {
      try {
        this.props.load(inputStream);
        return this;
      } finally {
        inputStream.close();
      }
    }

    public Builder withProps(Map kvprops) {
      props.putAll(kvprops);
      return this;
    }

    public Builder withPath(String basePath) {
      props.setProperty(BASE_PATH_PROP.key(), basePath);
      return this;
    }

    public Builder withSchema(String schemaStr) {
      props.setProperty(AVRO_SCHEMA.key(), schemaStr);
      return this;
    }

    public Builder withAvroSchemaValidate(boolean enable) {
      props.setProperty(AVRO_SCHEMA_VALIDATE.key(), String.valueOf(enable));
      return this;
    }

    public Builder forTable(String tableName) {
      props.setProperty(TABLE_NAME.key(), tableName);
      return this;
    }

    public Builder withPreCombineField(String preCombineField) {
      props.setProperty(PRECOMBINE_FIELD_PROP.key(), preCombineField);
      return this;
    }

    public Builder withWritePayLoad(String payload) {
      props.setProperty(WRITE_PAYLOAD_CLASS.key(), payload);
      return this;
    }

    public Builder withKeyGenerator(String keyGeneratorClass) {
      props.setProperty(KEYGENERATOR_CLASS_PROP.key(), keyGeneratorClass);
      return this;
    }

    public Builder withTimelineLayoutVersion(int version) {
      props.setProperty(TIMELINE_LAYOUT_VERSION.key(), String.valueOf(version));
      return this;
    }

    public Builder withBulkInsertParallelism(int bulkInsertParallelism) {
      props.setProperty(BULKINSERT_PARALLELISM.key(), String.valueOf(bulkInsertParallelism));
      return this;
    }

    public Builder withUserDefinedBulkInsertPartitionerClass(String className) {
      props.setProperty(BULKINSERT_USER_DEFINED_PARTITIONER_CLASS.key(), className);
      return this;
    }

    public Builder withDeleteParallelism(int parallelism) {
      props.setProperty(DELETE_PARALLELISM.key(), String.valueOf(parallelism));
      return this;
    }

    public Builder withParallelism(int insertShuffleParallelism, int upsertShuffleParallelism) {
      props.setProperty(INSERT_PARALLELISM.key(), String.valueOf(insertShuffleParallelism));
      props.setProperty(UPSERT_PARALLELISM.key(), String.valueOf(upsertShuffleParallelism));
      return this;
    }

    public Builder withRollbackParallelism(int rollbackParallelism) {
      props.setProperty(ROLLBACK_PARALLELISM.key(), String.valueOf(rollbackParallelism));
      return this;
    }

    public Builder withRollbackUsingMarkers(boolean rollbackUsingMarkers) {
      props.setProperty(ROLLBACK_USING_MARKERS.key(), String.valueOf(rollbackUsingMarkers));
      return this;
    }

    public Builder withWriteBufferLimitBytes(int writeBufferLimit) {
      props.setProperty(WRITE_BUFFER_LIMIT_BYTES.key(), String.valueOf(writeBufferLimit));
      return this;
    }

    public Builder combineInput(boolean onInsert, boolean onUpsert) {
      props.setProperty(COMBINE_BEFORE_INSERT_PROP.key(), String.valueOf(onInsert));
      props.setProperty(COMBINE_BEFORE_UPSERT_PROP.key(), String.valueOf(onUpsert));
      return this;
    }

    public Builder combineDeleteInput(boolean onDelete) {
      props.setProperty(COMBINE_BEFORE_DELETE_PROP.key(), String.valueOf(onDelete));
      return this;
    }

    public Builder withWriteStatusStorageLevel(String level) {
      props.setProperty(WRITE_STATUS_STORAGE_LEVEL.key(), level);
      return this;
    }

    public Builder withIndexConfig(HoodieIndexConfig indexConfig) {
      props.putAll(indexConfig.getProps());
      isIndexConfigSet = true;
      return this;
    }

    public Builder withStorageConfig(HoodieStorageConfig storageConfig) {
      props.putAll(storageConfig.getProps());
      isStorageConfigSet = true;
      return this;
    }

    public Builder withCompactionConfig(HoodieCompactionConfig compactionConfig) {
      props.putAll(compactionConfig.getProps());
      isCompactionConfigSet = true;
      return this;
    }

    public Builder withClusteringConfig(HoodieClusteringConfig clusteringConfig) {
      props.putAll(clusteringConfig.getProps());
      isClusteringConfigSet = true;
      return this;
    }

    public Builder withLockConfig(HoodieLockConfig lockConfig) {
      props.putAll(lockConfig.getProps());
      isLockConfigSet = true;
      return this;
    }

    public Builder withMetricsConfig(HoodieMetricsConfig metricsConfig) {
      props.putAll(metricsConfig.getProps());
      isMetricsConfigSet = true;
      return this;
    }

    public Builder withMemoryConfig(HoodieMemoryConfig memoryConfig) {
      props.putAll(memoryConfig.getProps());
      isMemoryConfigSet = true;
      return this;
    }

    public Builder withBootstrapConfig(HoodieBootstrapConfig bootstrapConfig) {
      props.putAll(bootstrapConfig.getProps());
      isBootstrapConfigSet = true;
      return this;
    }

    public Builder withPayloadConfig(HoodiePayloadConfig payloadConfig) {
      props.putAll(payloadConfig.getProps());
      isPayloadConfigSet = true;
      return this;
    }

    public Builder withMetadataConfig(HoodieMetadataConfig metadataConfig) {
      props.putAll(metadataConfig.getProps());
      isMetadataConfigSet = true;
      return this;
    }

    public Builder withAutoCommit(boolean autoCommit) {
      props.setProperty(HOODIE_AUTO_COMMIT_PROP.key(), String.valueOf(autoCommit));
      return this;
    }

    public Builder withWriteStatusClass(Class<? extends WriteStatus> writeStatusClass) {
      props.setProperty(HOODIE_WRITE_STATUS_CLASS_PROP.key(), writeStatusClass.getName());
      return this;
    }

    public Builder withFileSystemViewConfig(FileSystemViewStorageConfig viewStorageConfig) {
      props.putAll(viewStorageConfig.getProps());
      isViewConfigSet = true;
      return this;
    }

    public Builder withConsistencyGuardConfig(ConsistencyGuardConfig consistencyGuardConfig) {
      props.putAll(consistencyGuardConfig.getProps());
      isConsistencyGuardSet = true;
      return this;
    }

    public Builder withCallbackConfig(HoodieWriteCommitCallbackConfig callbackConfig) {
      props.putAll(callbackConfig.getProps());
      isCallbackConfigSet = true;
      return this;
    }

    public Builder withFinalizeWriteParallelism(int parallelism) {
      props.setProperty(FINALIZE_WRITE_PARALLELISM.key(), String.valueOf(parallelism));
      return this;
    }

    public Builder withMarkersDeleteParallelism(int parallelism) {
      props.setProperty(MARKERS_DELETE_PARALLELISM.key(), String.valueOf(parallelism));
      return this;
    }

    public Builder withEmbeddedTimelineServerEnabled(boolean enabled) {
      props.setProperty(EMBEDDED_TIMELINE_SERVER_ENABLED.key(), String.valueOf(enabled));
      return this;
    }

    public Builder withEmbeddedTimelineServerPort(int port) {
      props.setProperty(EMBEDDED_TIMELINE_SERVER_PORT.key(), String.valueOf(port));
      return this;
    }

    public Builder withBulkInsertSortMode(String mode) {
      props.setProperty(BULKINSERT_SORT_MODE.key(), mode);
      return this;
    }

    public Builder withAllowMultiWriteOnSameInstant(boolean allow) {
      props.setProperty(ALLOW_MULTI_WRITE_ON_SAME_INSTANT.key(), String.valueOf(allow));
      return this;
    }

    public Builder withExternalSchemaTrasformation(boolean enabled) {
      props.setProperty(EXTERNAL_RECORD_AND_SCHEMA_TRANSFORMATION.key(), String.valueOf(enabled));
      return this;
    }

    public Builder withMergeDataValidationCheckEnabled(boolean enabled) {
      props.setProperty(MERGE_DATA_VALIDATION_CHECK_ENABLED.key(), String.valueOf(enabled));
      return this;
    }

    public Builder withMergeAllowDuplicateOnInserts(boolean routeInsertsToNewFiles) {
      props.setProperty(MERGE_ALLOW_DUPLICATE_ON_INSERTS.key(), String.valueOf(routeInsertsToNewFiles));
      return this;
    }

    public Builder withHeartbeatIntervalInMs(Integer heartbeatIntervalInMs) {
      props.setProperty(CLIENT_HEARTBEAT_INTERVAL_IN_MS_PROP.key(), String.valueOf(heartbeatIntervalInMs));
      return this;
    }

    public Builder withHeartbeatTolerableMisses(Integer heartbeatTolerableMisses) {
      props.setProperty(CLIENT_HEARTBEAT_NUM_TOLERABLE_MISSES_PROP.key(), String.valueOf(heartbeatTolerableMisses));
      return this;
    }

    public Builder withWriteConcurrencyMode(WriteConcurrencyMode concurrencyMode) {
      props.setProperty(WRITE_CONCURRENCY_MODE_PROP.key(), concurrencyMode.value());
      return this;
    }

    public Builder withWriteMetaKeyPrefixes(String writeMetaKeyPrefixes) {
      props.setProperty(WRITE_META_KEY_PREFIXES_PROP.key(), writeMetaKeyPrefixes);
      return this;
    }

    public Builder withProperties(Properties properties) {
      this.props.putAll(properties);
      return this;
    }

    protected void setDefaults() {
      // Check for mandatory properties
      setDefaultValue(props, INSERT_PARALLELISM);
      setDefaultValue(props, BULKINSERT_PARALLELISM);
      setDefaultValue(props, UPSERT_PARALLELISM);
      setDefaultValue(props, DELETE_PARALLELISM);

      setDefaultValue(props, ROLLBACK_PARALLELISM);
      setDefaultValue(props, KEYGENERATOR_CLASS_PROP);
      setDefaultValue(props, WRITE_PAYLOAD_CLASS);
      setDefaultValue(props, ROLLBACK_USING_MARKERS);
      setDefaultValue(props, COMBINE_BEFORE_INSERT_PROP);
      setDefaultValue(props, COMBINE_BEFORE_UPSERT_PROP);
      setDefaultValue(props, COMBINE_BEFORE_DELETE_PROP);
      setDefaultValue(props, ALLOW_MULTI_WRITE_ON_SAME_INSTANT);
      setDefaultValue(props, WRITE_STATUS_STORAGE_LEVEL);
      setDefaultValue(props, HOODIE_AUTO_COMMIT_PROP);
      setDefaultValue(props, HOODIE_WRITE_STATUS_CLASS_PROP);
      setDefaultValue(props, FINALIZE_WRITE_PARALLELISM);
      setDefaultValue(props, MARKERS_DELETE_PARALLELISM);
      setDefaultValue(props, EMBEDDED_TIMELINE_SERVER_ENABLED);
      setDefaultValue(props, INITIAL_CONSISTENCY_CHECK_INTERVAL_MS_PROP);
      setDefaultValue(props, MAX_CONSISTENCY_CHECK_INTERVAL_MS_PROP);
      setDefaultValue(props, MAX_CONSISTENCY_CHECKS_PROP);
      setDefaultValue(props, FAIL_ON_TIMELINE_ARCHIVING_ENABLED_PROP);
      setDefaultValue(props, AVRO_SCHEMA_VALIDATE);
      setDefaultValue(props, BULKINSERT_SORT_MODE);
      setDefaultValue(props, MERGE_DATA_VALIDATION_CHECK_ENABLED);
      setDefaultValue(props, MERGE_ALLOW_DUPLICATE_ON_INSERTS);
      setDefaultValue(props, CLIENT_HEARTBEAT_INTERVAL_IN_MS_PROP);
      setDefaultValue(props, CLIENT_HEARTBEAT_NUM_TOLERABLE_MISSES_PROP);
      setDefaultValue(props, WRITE_CONCURRENCY_MODE_PROP);
      setDefaultValue(props, WRITE_META_KEY_PREFIXES_PROP);
      // Make sure the props is propagated
      setDefaultOnCondition(props, !isIndexConfigSet, HoodieIndexConfig.newBuilder().withEngineType(engineType).fromProperties(props).build());
      setDefaultOnCondition(props, !isStorageConfigSet, HoodieStorageConfig.newBuilder().fromProperties(props).build());
      setDefaultOnCondition(props, !isCompactionConfigSet,
          HoodieCompactionConfig.newBuilder().fromProperties(props).build());
      setDefaultOnCondition(props, !isClusteringConfigSet,
          HoodieClusteringConfig.newBuilder().fromProperties(props).build());
      setDefaultOnCondition(props, !isMetricsConfigSet, HoodieMetricsConfig.newBuilder().fromProperties(props).build());
      setDefaultOnCondition(props, !isBootstrapConfigSet,
          HoodieBootstrapConfig.newBuilder().fromProperties(props).build());
      setDefaultOnCondition(props, !isMemoryConfigSet, HoodieMemoryConfig.newBuilder().fromProperties(props).build());
      setDefaultOnCondition(props, !isViewConfigSet,
          FileSystemViewStorageConfig.newBuilder().fromProperties(props).build());
      setDefaultOnCondition(props, !isConsistencyGuardSet,
          ConsistencyGuardConfig.newBuilder().fromProperties(props).build());
      setDefaultOnCondition(props, !isCallbackConfigSet,
          HoodieWriteCommitCallbackConfig.newBuilder().fromProperties(props).build());
      setDefaultOnCondition(props, !isPayloadConfigSet,
          HoodiePayloadConfig.newBuilder().fromProperties(props).build());
      setDefaultOnCondition(props, !isMetadataConfigSet,
          HoodieMetadataConfig.newBuilder().fromProperties(props).build());
      setDefaultOnCondition(props, !isLockConfigSet,
          HoodieLockConfig.newBuilder().fromProperties(props).build());

      setDefaultValue(props, EXTERNAL_RECORD_AND_SCHEMA_TRANSFORMATION);
      setDefaultOnCondition(props, !props.containsKey(TIMELINE_LAYOUT_VERSION.key()), TIMELINE_LAYOUT_VERSION.key(),
          String.valueOf(TimelineLayoutVersion.CURR_VERSION));

    }

    private void validate() {
      String layoutVersion = getString(props, TIMELINE_LAYOUT_VERSION);
      // Ensure Layout Version is good
      new TimelineLayoutVersion(Integer.parseInt(layoutVersion));
      Objects.requireNonNull(getString(props, BASE_PATH_PROP));
      if (getString(props, WRITE_CONCURRENCY_MODE_PROP)
          .equalsIgnoreCase(WriteConcurrencyMode.OPTIMISTIC_CONCURRENCY_CONTROL.name())) {
        ValidationUtils.checkArgument(getString(props, HoodieCompactionConfig.FAILED_WRITES_CLEANER_POLICY_PROP)
            != HoodieFailedWritesCleaningPolicy.EAGER.name(), "To enable optimistic concurrency control, set hoodie.cleaner.policy.failed.writes=LAZY");
      }
    }

    public HoodieWriteConfig build() {
      setDefaults();
      validate();
      // Build WriteConfig at the end
      HoodieWriteConfig config = new HoodieWriteConfig(engineType, props);
      return config;
    }
  }
}

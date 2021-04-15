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

package org.apache.hudi.common.config;

import javax.annotation.concurrent.Immutable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

/**
 * Configurations used by the HUDI Metadata Table.
 */
@Immutable
public final class HoodieMetadataConfig extends HoodieConfig {

  public static final String METADATA_PREFIX = "hoodie.metadata";

  // Enable the internal Metadata Table which saves file listings
  public static final ConfigOption<Boolean> METADATA_ENABLE_PROP = ConfigOption
      .key(METADATA_PREFIX + ".enable")
      .defaultValue(false)
      .withVersion("0.7.0")
      .withDescription("Enable the internal Metadata Table which stores table level file listings");

  // Validate contents of Metadata Table on each access against the actual filesystem
  public static final ConfigOption<Boolean> METADATA_VALIDATE_PROP = ConfigOption
      .key(METADATA_PREFIX + ".validate")
      .defaultValue(false)
      .withVersion("0.7.0")
      .withDescription("Validate contents of Metadata Table on each access against the actual listings from DFS");

  public static final boolean DEFAULT_METADATA_ENABLE_FOR_READERS = false;

  // Enable metrics for internal Metadata Table
  public static final ConfigOption<Boolean> METADATA_METRICS_ENABLE_PROP = ConfigOption
      .key(METADATA_PREFIX + ".metrics.enable")
      .defaultValue(false)
      .withVersion("0.7.0")
      .withDescription("");

  // Parallelism for inserts
  public static final ConfigOption<Integer> METADATA_INSERT_PARALLELISM_PROP = ConfigOption
      .key(METADATA_PREFIX + ".insert.parallelism")
      .defaultValue(1)
      .withVersion("0.7.0")
      .withDescription("Parallelism to use when writing to the metadata table");

  // Async clean
  public static final ConfigOption<Boolean> METADATA_ASYNC_CLEAN_PROP = ConfigOption
      .key(METADATA_PREFIX + ".clean.async")
      .defaultValue(false)
      .withVersion("0.7.0")
      .withDescription("Enable asynchronous cleaning for metadata table");

  // Maximum delta commits before compaction occurs
  public static final ConfigOption<Integer> METADATA_COMPACT_NUM_DELTA_COMMITS_PROP = ConfigOption
      .key(METADATA_PREFIX + ".compact.max.delta.commits")
      .defaultValue(24)
      .withVersion("0.7.0")
      .withDescription("Controls how often the metadata table is compacted.");

  // Archival settings
  public static final ConfigOption<Integer> MIN_COMMITS_TO_KEEP_PROP = ConfigOption
      .key(METADATA_PREFIX + ".keep.min.commits")
      .defaultValue(20)
      .withVersion("0.7.0")
      .withDescription("Controls the archival of the metadata table’s timeline");

  public static final ConfigOption<Integer> MAX_COMMITS_TO_KEEP_PROP = ConfigOption
      .key(METADATA_PREFIX + ".keep.max.commits")
      .defaultValue(30)
      .withVersion("0.7.0")
      .withDescription("Controls the archival of the metadata table’s timeline");

  // Cleaner commits retained
  public static final ConfigOption<Integer> CLEANER_COMMITS_RETAINED_PROP = ConfigOption
      .key(METADATA_PREFIX + ".cleaner.commits.retained")
      .defaultValue(3)
      .withVersion("0.7.0")
      .withDescription("");

  // Controls whether or not, upon failure to fetch from metadata table, should fallback to listing.
  public static final ConfigOption<String> ENABLE_FALLBACK_PROP = ConfigOption
      .key(METADATA_PREFIX + ".fallback.enable")
      .defaultValue("true")
      .withVersion("0.7.0")
      .withDescription("Fallback to listing from DFS, if there are any errors in fetching from metadata table");

  // Regex to filter out matching directories during bootstrap
  public static final ConfigOption<String> DIRECTORY_FILTER_REGEX = ConfigOption
      .key(METADATA_PREFIX + ".dir.filter.regex")
      .defaultValue("")
      .withVersion("0.7.0")
      .withDescription("");

  public static final ConfigOption<String> HOODIE_ASSUME_DATE_PARTITIONING_PROP = ConfigOption
      .key("hoodie.assume.date.partitioning")
      .defaultValue("false")
      .withVersion("0.7.0")
      .withDescription("Should HoodieWriteClient assume the data is partitioned by dates, i.e three levels from base path. "
          + "This is a stop-gap to support tables created by versions < 0.3.1. Will be removed eventually");

  public static final ConfigOption<Integer> FILE_LISTING_PARALLELISM_PROP = ConfigOption
      .key("hoodie.file.listing.parallelism")
      .defaultValue(1500)
      .withVersion("0.7.0")
      .withDescription("");

  private HoodieMetadataConfig(Properties props) {
    super(props);
  }

  public static HoodieMetadataConfig.Builder newBuilder() {
    return new Builder();
  }

  public int getFileListingParallelism() {
    return Math.max(getInt(props, HoodieMetadataConfig.FILE_LISTING_PARALLELISM_PROP), 1);
  }

  public Boolean shouldAssumeDatePartitioning() {
    return getBoolean(props, HoodieMetadataConfig.HOODIE_ASSUME_DATE_PARTITIONING_PROP);
  }

  public boolean useFileListingMetadata() {
    return getBoolean(props, METADATA_ENABLE_PROP);
  }

  public boolean enableFallback() {
    return getBoolean(props, ENABLE_FALLBACK_PROP);
  }

  public boolean validateFileListingMetadata() {
    return getBoolean(props, METADATA_VALIDATE_PROP);
  }

  public boolean enableMetrics() {
    return getBoolean(props, METADATA_METRICS_ENABLE_PROP);
  }

  public String getDirectoryFilterRegex() {
    return getString(props, DIRECTORY_FILTER_REGEX);
  }

  public static class Builder {

    private final Properties props = new Properties();

    public Builder fromFile(File propertiesFile) throws IOException {
      try (FileReader reader = new FileReader(propertiesFile)) {
        this.props.load(reader);
        return this;
      }
    }

    public Builder fromProperties(Properties props) {
      this.props.putAll(props);
      return this;
    }

    public Builder enable(boolean enable) {
      set(props, METADATA_ENABLE_PROP, String.valueOf(enable));
      return this;
    }

    public Builder enableMetrics(boolean enableMetrics) {
      set(props, METADATA_METRICS_ENABLE_PROP, String.valueOf(enableMetrics));
      return this;
    }

    public Builder enableFallback(boolean fallback) {
      set(props, ENABLE_FALLBACK_PROP, String.valueOf(fallback));
      return this;
    }

    public Builder validate(boolean validate) {
      set(props, METADATA_VALIDATE_PROP, String.valueOf(validate));
      return this;
    }

    public Builder withInsertParallelism(int parallelism) {
      set(props, METADATA_INSERT_PARALLELISM_PROP, String.valueOf(parallelism));
      return this;
    }

    public Builder withAsyncClean(boolean asyncClean) {
      set(props, METADATA_ASYNC_CLEAN_PROP, String.valueOf(asyncClean));
      return this;
    }

    public Builder withMaxNumDeltaCommitsBeforeCompaction(int maxNumDeltaCommitsBeforeCompaction) {
      set(props, METADATA_COMPACT_NUM_DELTA_COMMITS_PROP, String.valueOf(maxNumDeltaCommitsBeforeCompaction));
      return this;
    }

    public Builder archiveCommitsWith(int minToKeep, int maxToKeep) {
      set(props, MIN_COMMITS_TO_KEEP_PROP, String.valueOf(minToKeep));
      set(props, MAX_COMMITS_TO_KEEP_PROP, String.valueOf(maxToKeep));
      return this;
    }

    public Builder retainCommits(int commitsRetained) {
      set(props, CLEANER_COMMITS_RETAINED_PROP, String.valueOf(commitsRetained));
      return this;
    }

    public Builder withFileListingParallelism(int parallelism) {
      set(props, FILE_LISTING_PARALLELISM_PROP, String.valueOf(parallelism));
      return this;
    }

    public Builder withAssumeDatePartitioning(boolean assumeDatePartitioning) {
      set(props, HOODIE_ASSUME_DATE_PARTITIONING_PROP, String.valueOf(assumeDatePartitioning));
      return this;
    }

    public Builder withDirectoryFilterRegex(String regex) {
      set(props, DIRECTORY_FILTER_REGEX, regex);
      return this;
    }

    public HoodieMetadataConfig build() {
      HoodieMetadataConfig config = new HoodieMetadataConfig(props);
      setDefaultValue(props, METADATA_ENABLE_PROP);
      setDefaultValue(props, METADATA_METRICS_ENABLE_PROP);
      setDefaultValue(props, METADATA_VALIDATE_PROP);
      setDefaultValue(props, METADATA_INSERT_PARALLELISM_PROP);
      setDefaultValue(props, METADATA_ASYNC_CLEAN_PROP);
      setDefaultValue(props, METADATA_COMPACT_NUM_DELTA_COMMITS_PROP);
      setDefaultValue(props, CLEANER_COMMITS_RETAINED_PROP);
      setDefaultValue(props, MAX_COMMITS_TO_KEEP_PROP);
      setDefaultValue(props, MIN_COMMITS_TO_KEEP_PROP);
      setDefaultValue(props, FILE_LISTING_PARALLELISM_PROP);
      setDefaultValue(props, HOODIE_ASSUME_DATE_PARTITIONING_PROP);
      setDefaultValue(props, ENABLE_FALLBACK_PROP);
      setDefaultValue(props, DIRECTORY_FILTER_REGEX);
      return config;
    }
  }
}

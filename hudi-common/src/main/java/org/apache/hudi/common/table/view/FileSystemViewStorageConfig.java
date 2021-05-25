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

package org.apache.hudi.common.table.view;

import org.apache.hudi.common.config.ConfigOption;
import org.apache.hudi.common.config.HoodieConfig;
import org.apache.hudi.common.util.ValidationUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

/**
 * File System View Storage Configurations.
 */
public class FileSystemViewStorageConfig extends HoodieConfig {

  // Property Names
  public static final ConfigOption<FileSystemViewStorageType> FILESYSTEM_VIEW_STORAGE_TYPE = ConfigOption
      .key("hoodie.filesystem.view.type")
      .defaultValue(FileSystemViewStorageType.MEMORY)
      .withDescription("");

  public static final ConfigOption<String> FILESYSTEM_VIEW_INCREMENTAL_SYNC_MODE = ConfigOption
      .key("hoodie.filesystem.view.incr.timeline.sync.enable")
      .defaultValue("false")
      .withDescription("");

  public static final ConfigOption<FileSystemViewStorageType> FILESYSTEM_SECONDARY_VIEW_STORAGE_TYPE = ConfigOption
      .key("hoodie.filesystem.view.secondary.type")
      .defaultValue(FileSystemViewStorageType.MEMORY)
      .withDescription("");

  public static final ConfigOption<String> FILESYSTEM_VIEW_REMOTE_HOST = ConfigOption
      .key("hoodie.filesystem.view.remote.host")
      .defaultValue("localhost")
      .withDescription("");

  public static final ConfigOption<Integer> FILESYSTEM_VIEW_REMOTE_PORT = ConfigOption
      .key("hoodie.filesystem.view.remote.port")
      .defaultValue(26754)
      .withDescription("");

  public static final ConfigOption<String> FILESYSTEM_VIEW_SPILLABLE_DIR = ConfigOption
      .key("hoodie.filesystem.view.spillable.dir")
      .defaultValue("/tmp/view_map/")
      .withDescription("");

  public static final ConfigOption<Long> FILESYSTEM_VIEW_SPILLABLE_MEM = ConfigOption
      .key("hoodie.filesystem.view.spillable.mem")
      .defaultValue(100 * 1024 * 1024L) // 100 MB
      .withDescription("");

  public static final ConfigOption<Double> FILESYSTEM_VIEW_PENDING_COMPACTION_MEM_FRACTION = ConfigOption
      .key("hoodie.filesystem.view.spillable.compaction.mem.fraction")
      .defaultValue(0.8)
      .withDescription("");

  public static final ConfigOption<Double> FILESYSTEM_VIEW_BOOTSTRAP_BASE_FILE_FRACTION = ConfigOption
      .key("hoodie.filesystem.view.spillable.bootstrap.base.file.mem.fraction")
      .defaultValue(0.05)
      .withDescription("");

  public static final ConfigOption<Double> FILESYSTEM_VIEW_REPLACED_MEM_FRACTION = ConfigOption
      .key("hoodie.filesystem.view.spillable.replaced.mem.fraction")
      .defaultValue(0.01)
      .withDescription("");

  public static final ConfigOption<Double> FILESYSTEM_VIEW_PENDING_CLUSTERING_MEM_FRACTION = ConfigOption
      .key("hoodie.filesystem.view.spillable.clustering.mem.fraction")
      .defaultValue(0.01)
      .withDescription("");

  public static final ConfigOption<String> ROCKSDB_BASE_PATH_PROP = ConfigOption
      .key("hoodie.filesystem.view.rocksdb.base.path")
      .defaultValue("/tmp/hoodie_timeline_rocksdb")
      .withDescription("");

  public static final ConfigOption<Integer> FILESTYSTEM_REMOTE_TIMELINE_CLIENT_TIMEOUT_SECS = ConfigOption
      .key("hoodie.filesystem.view.remote.timeout.secs")
      .defaultValue(5 * 60) // 5 min
      .withDescription("");

  /**
   * Configs to control whether backup needs to be configured if clients were not able to reach
   * timeline service.
   */
  public static final ConfigOption<String> REMOTE_BACKUP_VIEW_HANDLER_ENABLE = ConfigOption
      .key("hoodie.filesystem.remote.backup.view.enable")
      .defaultValue("true") // Need to be disabled only for tests.
      .withDescription("");

  public static FileSystemViewStorageConfig.Builder newBuilder() {
    return new Builder();
  }

  private FileSystemViewStorageConfig() {
    super();
  }

  public FileSystemViewStorageType getStorageType() {
    return FileSystemViewStorageType.valueOf(getString(FILESYSTEM_VIEW_STORAGE_TYPE));
  }

  public boolean isIncrementalTimelineSyncEnabled() {
    return getBoolean(FILESYSTEM_VIEW_INCREMENTAL_SYNC_MODE);
  }

  public String getRemoteViewServerHost() {
    return getString(FILESYSTEM_VIEW_REMOTE_HOST);
  }

  public Integer getRemoteViewServerPort() {
    return getInt(FILESYSTEM_VIEW_REMOTE_PORT);
  }

  public Integer getRemoteTimelineClientTimeoutSecs() {
    return getInt(FILESTYSTEM_REMOTE_TIMELINE_CLIENT_TIMEOUT_SECS);
  }

  public long getMaxMemoryForFileGroupMap() {
    long totalMemory = getLong(FILESYSTEM_VIEW_SPILLABLE_MEM);
    return totalMemory - getMaxMemoryForPendingCompaction() - getMaxMemoryForBootstrapBaseFile();
  }

  public long getMaxMemoryForPendingCompaction() {
    long totalMemory = getLong(FILESYSTEM_VIEW_SPILLABLE_MEM);
    return new Double(totalMemory * getDouble(FILESYSTEM_VIEW_PENDING_COMPACTION_MEM_FRACTION))
        .longValue();
  }

  public long getMaxMemoryForBootstrapBaseFile() {
    long totalMemory = getLong(FILESYSTEM_VIEW_SPILLABLE_MEM);
    long reservedForExternalDataFile =
        new Double(totalMemory * getDouble(FILESYSTEM_VIEW_BOOTSTRAP_BASE_FILE_FRACTION))
            .longValue();
    return reservedForExternalDataFile;
  }

  public long getMaxMemoryForReplacedFileGroups() {
    long totalMemory = getLong(FILESYSTEM_VIEW_SPILLABLE_MEM);
    return new Double(totalMemory * getDouble(FILESYSTEM_VIEW_REPLACED_MEM_FRACTION))
        .longValue();
  }

  public long getMaxMemoryForPendingClusteringFileGroups() {
    long totalMemory = getLong(FILESYSTEM_VIEW_SPILLABLE_MEM);
    return new Double(totalMemory * getDouble(FILESYSTEM_VIEW_PENDING_CLUSTERING_MEM_FRACTION))
        .longValue();
  }

  public String getSpillableDir() {
    return getString(FILESYSTEM_VIEW_SPILLABLE_DIR);
  }

  public FileSystemViewStorageType getSecondaryStorageType() {
    return FileSystemViewStorageType.valueOf(getString(FILESYSTEM_SECONDARY_VIEW_STORAGE_TYPE));
  }

  public boolean shouldEnableBackupForRemoteFileSystemView() {
    return getBoolean(REMOTE_BACKUP_VIEW_HANDLER_ENABLE);
  }

  public String getRocksdbBasePath() {
    return getString(ROCKSDB_BASE_PATH_PROP);
  }

  /**
   * The builder used to build {@link FileSystemViewStorageConfig}.
   */
  public static class Builder {

    private final FileSystemViewStorageConfig fileSystemViewStorageConfig = new FileSystemViewStorageConfig();

    public Builder fromFile(File propertiesFile) throws IOException {
      try (FileReader reader = new FileReader(propertiesFile)) {
        fileSystemViewStorageConfig.getProps().load(reader);
        return this;
      }
    }

    public Builder fromProperties(Properties props) {
      this.fileSystemViewStorageConfig.getProps().putAll(props);
      return this;
    }

    public Builder withStorageType(FileSystemViewStorageType storageType) {
      fileSystemViewStorageConfig.set(FILESYSTEM_VIEW_STORAGE_TYPE, storageType.name());
      return this;
    }

    public Builder withSecondaryStorageType(FileSystemViewStorageType storageType) {
      fileSystemViewStorageConfig.set(FILESYSTEM_SECONDARY_VIEW_STORAGE_TYPE, storageType.name());
      return this;
    }

    public Builder withIncrementalTimelineSync(boolean enableIncrTimelineSync) {
      fileSystemViewStorageConfig.set(FILESYSTEM_VIEW_INCREMENTAL_SYNC_MODE, Boolean.toString(enableIncrTimelineSync));
      return this;
    }

    public Builder withRemoteServerHost(String remoteServerHost) {
      fileSystemViewStorageConfig.set(FILESYSTEM_VIEW_REMOTE_HOST, remoteServerHost);
      return this;
    }

    public Builder withRemoteServerPort(Integer remoteServerPort) {
      fileSystemViewStorageConfig.set(FILESYSTEM_VIEW_REMOTE_PORT, remoteServerPort.toString());
      return this;
    }

    public Builder withMaxMemoryForView(Long maxMemoryForView) {
      fileSystemViewStorageConfig.set(FILESYSTEM_VIEW_SPILLABLE_MEM, maxMemoryForView.toString());
      return this;
    }

    public Builder withRemoteTimelineClientTimeoutSecs(Long timelineClientTimeoutSecs) {
      fileSystemViewStorageConfig.set(FILESTYSTEM_REMOTE_TIMELINE_CLIENT_TIMEOUT_SECS, timelineClientTimeoutSecs.toString());
      return this;
    }

    public Builder withMemFractionForPendingCompaction(Double memFractionForPendingCompaction) {
      fileSystemViewStorageConfig.set(FILESYSTEM_VIEW_PENDING_COMPACTION_MEM_FRACTION, memFractionForPendingCompaction.toString());
      return this;
    }

    public Builder withMemFractionForExternalDataFile(Double memFractionForExternalDataFile) {
      fileSystemViewStorageConfig.set(FILESYSTEM_VIEW_BOOTSTRAP_BASE_FILE_FRACTION, memFractionForExternalDataFile.toString());
      return this;
    }

    public Builder withBaseStoreDir(String baseStorePath) {
      fileSystemViewStorageConfig.set(FILESYSTEM_VIEW_SPILLABLE_DIR, baseStorePath);
      return this;
    }

    public Builder withRocksDBPath(String basePath) {
      fileSystemViewStorageConfig.set(ROCKSDB_BASE_PATH_PROP, basePath);
      return this;
    }

    public Builder withEnableBackupForRemoteFileSystemView(boolean enable) {
      fileSystemViewStorageConfig.set(REMOTE_BACKUP_VIEW_HANDLER_ENABLE, Boolean.toString(enable));
      return this;
    }

    public FileSystemViewStorageConfig build() {
      fileSystemViewStorageConfig.setDefaultValue(FILESYSTEM_VIEW_STORAGE_TYPE);
      fileSystemViewStorageConfig.setDefaultValue(FILESYSTEM_VIEW_INCREMENTAL_SYNC_MODE);
      fileSystemViewStorageConfig.setDefaultValue(FILESYSTEM_SECONDARY_VIEW_STORAGE_TYPE);
      fileSystemViewStorageConfig.setDefaultValue(FILESYSTEM_VIEW_REMOTE_HOST);
      fileSystemViewStorageConfig.setDefaultValue(FILESYSTEM_VIEW_REMOTE_PORT);
      fileSystemViewStorageConfig.setDefaultValue(FILESYSTEM_VIEW_SPILLABLE_DIR);
      fileSystemViewStorageConfig.setDefaultValue(FILESYSTEM_VIEW_SPILLABLE_MEM);
      fileSystemViewStorageConfig.setDefaultValue(FILESTYSTEM_REMOTE_TIMELINE_CLIENT_TIMEOUT_SECS);
      fileSystemViewStorageConfig.setDefaultValue(FILESYSTEM_VIEW_PENDING_COMPACTION_MEM_FRACTION);
      fileSystemViewStorageConfig.setDefaultValue(FILESYSTEM_VIEW_BOOTSTRAP_BASE_FILE_FRACTION);
      fileSystemViewStorageConfig.setDefaultValue(FILESYSTEM_VIEW_REPLACED_MEM_FRACTION);
      fileSystemViewStorageConfig.setDefaultValue(FILESYSTEM_VIEW_PENDING_CLUSTERING_MEM_FRACTION);
      fileSystemViewStorageConfig.setDefaultValue(ROCKSDB_BASE_PATH_PROP);
      fileSystemViewStorageConfig.setDefaultValue(REMOTE_BACKUP_VIEW_HANDLER_ENABLE);

      // Validations
      FileSystemViewStorageType.valueOf(fileSystemViewStorageConfig.getString(FILESYSTEM_VIEW_STORAGE_TYPE));
      FileSystemViewStorageType.valueOf(fileSystemViewStorageConfig.getString(FILESYSTEM_SECONDARY_VIEW_STORAGE_TYPE));
      ValidationUtils.checkArgument(fileSystemViewStorageConfig.getInt(FILESYSTEM_VIEW_REMOTE_PORT) > 0);
      return fileSystemViewStorageConfig;
    }
  }

}

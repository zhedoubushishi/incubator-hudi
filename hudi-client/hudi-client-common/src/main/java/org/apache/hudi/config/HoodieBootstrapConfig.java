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

import org.apache.hudi.client.bootstrap.BootstrapMode;
import org.apache.hudi.client.bootstrap.selector.MetadataOnlyBootstrapModeSelector;
import org.apache.hudi.client.bootstrap.translator.IdentityBootstrapPartitionPathTranslator;
import org.apache.hudi.common.bootstrap.index.HFileBootstrapIndex;
import org.apache.hudi.common.config.ConfigOption;
import org.apache.hudi.common.config.HoodieConfig;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;
import org.apache.hudi.common.table.HoodieTableConfig;

/**
 * Bootstrap specific configs.
 */
public class HoodieBootstrapConfig extends HoodieConfig {

  public static final ConfigOption<String> BOOTSTRAP_BASE_PATH_PROP = ConfigOption
      .key("hoodie.bootstrap.base.path")
      .noDefaultValue()
      .sinceVersion("0.6.0")
      .withDocumentation("Base path of the dataset that needs to be bootstrapped as a Hudi table");

  public static final ConfigOption<String> BOOTSTRAP_MODE_SELECTOR = ConfigOption
      .key("hoodie.bootstrap.mode.selector")
      .defaultValue(MetadataOnlyBootstrapModeSelector.class.getCanonicalName())
      .sinceVersion("0.6.0")
      .withDocumentation("Selects the mode in which each file/partition in the bootstrapped dataset gets bootstrapped");

  public static final ConfigOption<String> FULL_BOOTSTRAP_INPUT_PROVIDER = ConfigOption
      .key("hoodie.bootstrap.full.input.provider")
      .defaultValue("org.apache.hudi.bootstrap.SparkParquetBootstrapDataProvider")
      .sinceVersion("0.6.0")
      .withDocumentation("Class to use for reading the bootstrap dataset partitions/files, for Bootstrap mode FULL_RECORD");

  public static final ConfigOption<String> BOOTSTRAP_KEYGEN_CLASS = ConfigOption
      .key("hoodie.bootstrap.keygen.class")
      .noDefaultValue()
      .sinceVersion("0.6.0")
      .withDocumentation("Key generator implementation to be used for generating keys from the bootstrapped dataset");

  public static final ConfigOption<String> BOOTSTRAP_PARTITION_PATH_TRANSLATOR_CLASS = ConfigOption
      .key("hoodie.bootstrap.partitionpath.translator.class")
      .defaultValue(IdentityBootstrapPartitionPathTranslator.class.getName())
      .sinceVersion("0.6.0")
      .withDocumentation("Translates the partition paths from the bootstrapped data into how is laid out as a Hudi table.");

  public static final ConfigOption<String> BOOTSTRAP_PARALLELISM = ConfigOption
      .key("hoodie.bootstrap.parallelism")
      .defaultValue("1500")
      .sinceVersion("0.6.0")
      .withDocumentation("Parallelism value to be used to bootstrap data into hudi");

  public static final ConfigOption<String> BOOTSTRAP_MODE_SELECTOR_REGEX = ConfigOption
      .key("hoodie.bootstrap.mode.selector.regex")
      .defaultValue(".*")
      .sinceVersion("0.6.0")
      .withDocumentation("Matches each bootstrap dataset partition against this regex and applies the mode below to it.");

  public static final ConfigOption<String> BOOTSTRAP_MODE_SELECTOR_REGEX_MODE = ConfigOption
      .key("hoodie.bootstrap.mode.selector.regex.mode")
      .defaultValue(BootstrapMode.METADATA_ONLY.name())
      .sinceVersion("0.6.0")
      .withDocumentation("Bootstrap mode to apply for partition paths, that match regex above. "
          + "METADATA_ONLY will generate just skeleton base files with keys/footers, avoiding full cost of rewriting the dataset. "
          + "FULL_RECORD will perform a full copy/rewrite of the data as a Hudi table.");

  public static final ConfigOption<String> BOOTSTRAP_INDEX_CLASS_PROP = ConfigOption
      .key("hoodie.bootstrap.index.class")
      .defaultValue(HFileBootstrapIndex.class.getName())
      .sinceVersion("0.6.0")
      .withDocumentation("");

  private HoodieBootstrapConfig() {
    super();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {

    private final HoodieBootstrapConfig bootstrapConfig = new HoodieBootstrapConfig();

    public Builder fromFile(File propertiesFile) throws IOException {
      try (FileReader reader = new FileReader(propertiesFile)) {
        this.bootstrapConfig.getProps().load(reader);
        return this;
      }
    }

    public Builder withBootstrapBasePath(String basePath) {
      bootstrapConfig.setValue(BOOTSTRAP_BASE_PATH_PROP, basePath);
      return this;
    }

    public Builder withBootstrapModeSelector(String partitionSelectorClass) {
      bootstrapConfig.setValue(BOOTSTRAP_MODE_SELECTOR, partitionSelectorClass);
      return this;
    }

    public Builder withFullBootstrapInputProvider(String partitionSelectorClass) {
      bootstrapConfig.setValue(FULL_BOOTSTRAP_INPUT_PROVIDER, partitionSelectorClass);
      return this;
    }

    public Builder withBootstrapKeyGenClass(String keyGenClass) {
      bootstrapConfig.setValue(BOOTSTRAP_KEYGEN_CLASS, keyGenClass);
      return this;
    }

    public Builder withBootstrapPartitionPathTranslatorClass(String partitionPathTranslatorClass) {
      bootstrapConfig
          .setValue(BOOTSTRAP_PARTITION_PATH_TRANSLATOR_CLASS, partitionPathTranslatorClass);
      return this;
    }

    public Builder withBootstrapParallelism(int parallelism) {
      bootstrapConfig.setValue(BOOTSTRAP_PARALLELISM, String.valueOf(parallelism));
      return this;
    }

    public Builder withBootstrapModeSelectorRegex(String regex) {
      bootstrapConfig.setValue(BOOTSTRAP_MODE_SELECTOR_REGEX, regex);
      return this;
    }

    public Builder withBootstrapModeForRegexMatch(BootstrapMode modeForRegexMatch) {
      bootstrapConfig.setValue(BOOTSTRAP_MODE_SELECTOR_REGEX_MODE, modeForRegexMatch.name());
      return this;
    }

    public Builder fromProperties(Properties props) {
      this.bootstrapConfig.getProps().putAll(props);
      return this;
    }

    public HoodieBootstrapConfig build() {
      bootstrapConfig.setDefaultValue(BOOTSTRAP_PARALLELISM);
      bootstrapConfig.setDefaultValue(BOOTSTRAP_PARTITION_PATH_TRANSLATOR_CLASS);
      bootstrapConfig.setDefaultValue(BOOTSTRAP_MODE_SELECTOR);
      bootstrapConfig.setDefaultValue(BOOTSTRAP_MODE_SELECTOR_REGEX);
      bootstrapConfig.setDefaultValue(BOOTSTRAP_MODE_SELECTOR_REGEX_MODE);
      bootstrapConfig.setDefaultValue(BOOTSTRAP_INDEX_CLASS_PROP, HoodieTableConfig.getDefaultBootstrapIndexClass(
          bootstrapConfig.getProps()));
      bootstrapConfig.setDefaultValue(FULL_BOOTSTRAP_INPUT_PROVIDER);
      return bootstrapConfig;
    }
  }
}

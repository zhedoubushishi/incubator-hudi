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

import org.apache.hudi.common.config.HoodieConfig;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

import static org.apache.hudi.common.model.HoodiePayloadProps.PAYLOAD_EVENT_TIME_FIELD_PROP;
import static org.apache.hudi.common.model.HoodiePayloadProps.PAYLOAD_ORDERING_FIELD_PROP;

/**
 * Hoodie payload related configs.
 */
public class HoodiePayloadConfig extends HoodieConfig {

  private HoodiePayloadConfig() {
    super();
  }

  public static HoodiePayloadConfig.Builder newBuilder() {
    return new HoodiePayloadConfig.Builder();
  }

  public static class Builder {

    private final HoodiePayloadConfig payloadConfig = new HoodiePayloadConfig();

    public Builder fromFile(File propertiesFile) throws IOException {
      try (FileReader reader = new FileReader(propertiesFile)) {
        this.payloadConfig.getProps().load(reader);
        return this;
      }
    }

    public Builder fromProperties(Properties props) {
      this.payloadConfig.getProps().putAll(props);
      return this;
    }

    public Builder withPayloadOrderingField(String payloadOrderingField) {
      payloadConfig.set(PAYLOAD_ORDERING_FIELD_PROP, String.valueOf(payloadOrderingField));
      return this;
    }

    public Builder withPayloadEventTimeField(String payloadEventTimeField) {
      payloadConfig.set(PAYLOAD_EVENT_TIME_FIELD_PROP, String.valueOf(payloadEventTimeField));
      return this;
    }

    public HoodiePayloadConfig build() {
      payloadConfig.setDefaultValue(PAYLOAD_ORDERING_FIELD_PROP);
      payloadConfig.setDefaultValue(PAYLOAD_EVENT_TIME_FIELD_PROP);
      return payloadConfig;
    }
  }

}

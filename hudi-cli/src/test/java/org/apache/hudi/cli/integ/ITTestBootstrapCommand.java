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

package org.apache.hudi.cli.integ;

import org.apache.hudi.cli.HoodiePrintHelper;
import org.apache.hudi.cli.commands.TableCommand;
import org.apache.hudi.cli.testutils.AbstractShellIntegrationTest;
import org.apache.hudi.common.model.HoodieTableType;
import org.apache.hudi.common.table.timeline.versioning.TimelineLayoutVersion;
import org.apache.hudi.testutils.HoodieTestDataGenerator;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.shell.core.CommandResult;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class of {@link org.apache.hudi.cli.commands.BootstrapCommand}.
 */
public class ITTestBootstrapCommand extends AbstractShellIntegrationTest {

  private static final int TOTAL_RECORDS = 100;
  private static final String PARTITION_FIELD = "datestr";
  private static final String RECORD_KEY_FIELD = "_row_key";

  private String srcPath;
  private List<String> partitions;

  @BeforeEach
  public void init() throws IOException {
    String srcName = "src_table";
    String tableName = "test_table";
    srcPath = basePath + File.separator + srcName;
    String tablePath = basePath + File.separator + tableName;

    partitions = Arrays.asList("2020/04/01", "2020/04/02", "2020/04/03");
    double timestamp = new Double(Instant.now().toEpochMilli()).longValue();
    Dataset<Row> df = HoodieTestDataGenerator.generateTestRawTripDataset(timestamp,
        TOTAL_RECORDS, partitions, jsc, sqlContext);
    df.write().partitionBy("datestr").format("parquet").mode(SaveMode.Overwrite).save(srcPath);

    // Create table and connect
    new TableCommand().createTable(
        tablePath, tableName, HoodieTableType.COPY_ON_WRITE.name(),
        "", TimelineLayoutVersion.VERSION_1, "org.apache.hudi.common.model.HoodieAvroPayload",
        "org.apache.hudi.common.bootstrap.index.HFileBasedBootstrapIndex");
  }

  /**
   * Test case for command 'bootstrap'.
   */
  @Test
  public void testBootstrapCommand() {
    // test bootstrap run command
    String cmdStr = String.format("bootstrap run --sourcePath %s --recordKeyColumns %s --partitionFields %s", srcPath, RECORD_KEY_FIELD, PARTITION_FIELD);
    CommandResult cr = getShell().executeCommand(cmdStr);
    assertTrue(cr.isSuccess());

    // test bootstrap indexed partitions command
    CommandResult cr2 = getShell().executeCommand("bootstrap show indexed partitions");
    assertTrue(cr2.isSuccess());

    String[] header = new String[] {"Indexed partitions"};
    String[][] rows = new String[partitions.size()][1];
    for (int i = 0; i < partitions.size(); i++) {
      rows[i][0] = PARTITION_FIELD + "=" + partitions.get(i);
    }
    String expect = HoodiePrintHelper.print(header, rows);
    expect = removeNonWordAndStripSpace(expect);
    String got = removeNonWordAndStripSpace(cr2.getResult().toString());
    assertEquals(expect, got);
  }
}
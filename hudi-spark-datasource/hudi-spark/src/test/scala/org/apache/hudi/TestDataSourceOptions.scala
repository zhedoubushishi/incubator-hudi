/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi

import org.apache.hudi.DataSourceWriteOptions._
import org.apache.hudi.hive.MultiPartKeysValueExtractor
import org.apache.hudi.keygen.ComplexKeyGenerator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestDataSourceOptions {
  @Test def inferDataSourceOptions(): Unit = {
    val inputOptions1 = Map(
      TABLE_NAME_OPT_KEY -> "hudi_table",
      PARTITIONPATH_FIELD_OPT_KEY -> "year,month"
    )
    val modifiedOptions1 = HoodieWriterUtils.parametersWithWriteDefaults(inputOptions1)
    assertEquals(classOf[ComplexKeyGenerator].getName, modifiedOptions1(KEYGENERATOR_CLASS_OPT_KEY))
    assertEquals("hudi_table", modifiedOptions1(HIVE_TABLE_OPT_KEY))
    assertEquals("year,month", modifiedOptions1(HIVE_PARTITION_FIELDS_OPT_KEY))
    assertEquals(classOf[MultiPartKeysValueExtractor].getName,
      modifiedOptions1(HIVE_PARTITION_EXTRACTOR_CLASS_OPT_KEY))
  }
}
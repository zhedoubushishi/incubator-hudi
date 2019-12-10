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

package org.apache.hudi.utilities.sources.helpers;

import java.util.*;
import java.util.stream.Collectors;
import org.apache.kafka.common.TopicPartition;
import org.apache.hudi.DataSourceUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.TypedProperties;
import org.apache.hudi.exception.HoodieNotSupportedException;
import org.apache.hudi.utilities.exception.HoodieDeltaStreamerException;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.kafka.clients.consumer.KafkaConsumer;
//import org.apache.spark.streaming.kafka.KafkaCluster;
//import org.apache.spark.streaming.kafka.KafkaCluster.LeaderOffset;
import org.apache.spark.streaming.kafka010.OffsetRange;
import scala.Predef;
import scala.collection.JavaConverters;
//import scala.collection.immutable.Map;
//import scala.collection.immutable.Set;
import scala.collection.mutable.ArrayBuffer;
import scala.collection.mutable.StringBuilder;
import scala.util.Either;

import org.apache.kafka.common.PartitionInfo;

/**
 * Source to read data from Kafka, incrementally
 */
public class KafkaOffsetGen {

  private static volatile Logger log = LogManager.getLogger(KafkaOffsetGen.class);

  private static long DEFAULT_MAX_EVENTS_TO_READ = 1000000; // 1M events max

  public static class CheckpointUtils {

    /**
     * Reconstruct checkpoint from string.
     */
    public static HashMap<TopicPartition, Long> strToOffsets(String checkpointStr) {
      HashMap<TopicPartition, Long> offsetMap = new HashMap<>();
      if (checkpointStr.length() == 0) {
        return offsetMap;
      }
      String[] splits = checkpointStr.split(",");
      String topic = splits[0];
      for (int i = 1; i < splits.length; i++) {
        String[] subSplits = splits[i].split(":");
        offsetMap.put(new TopicPartition(topic, Integer.parseInt(subSplits[0])), Long.parseLong(subSplits[1]));
      }
      return offsetMap;
    }

    /**
     * String representation of checkpoint
     * <p>
     * Format: topic1,0:offset0,1:offset1,2:offset2, .....
     */
    public static String offsetsToStr(OffsetRange[] ranges) {
      StringBuilder sb = new StringBuilder();
      // at least 1 partition will be present.
      sb.append(ranges[0].topic() + ",");
      sb.append(Arrays.stream(ranges).map(r -> String.format("%s:%d", r.partition(), r.untilOffset()))
              .collect(Collectors.joining(",")));
      return sb.toString();
    }

    /**
     * Compute the offset ranges to read from Kafka, while handling newly added partitions, skews, event limits.
     *
     * @param fromOffsetMap offsets where we left off last time
     * @param toOffsetMap offsets of where each partitions is currently at
     * @param numEvents maximum number of events to read.
     */
    public static OffsetRange[] computeOffsetRanges(HashMap<TopicPartition, Long> fromOffsetMap,
                                                    HashMap<TopicPartition, Long> toOffsetMap, long numEvents) {

      Comparator<OffsetRange> byPartition = Comparator.comparing(OffsetRange::partition);

      // Create initial offset ranges for each 'to' partition, with from = to offsets.
      OffsetRange[] ranges = new OffsetRange[toOffsetMap.size()];
      toOffsetMap.entrySet().stream().map(e -> {
        TopicPartition tp = e.getKey();
        long fromOffset = fromOffsetMap.getOrDefault(tp, 0L);
        return OffsetRange.create(tp.topic(), tp.partition(), fromOffset, fromOffset);
      }).sorted(byPartition).collect(Collectors.toList()).toArray(ranges);

      long allocedEvents = 0;
      java.util.Set<Integer> exhaustedPartitions = new HashSet<>();
      // keep going until we have events to allocate and partitions still not exhausted.
      while (allocedEvents < numEvents && exhaustedPartitions.size() < toOffsetMap.size()) {
        long remainingEvents = numEvents - allocedEvents;
        long eventsPerPartition =
                (long) Math.ceil((1.0 * remainingEvents) / (toOffsetMap.size() - exhaustedPartitions.size()));

        // Allocate the remaining events to non-exhausted partitions, in round robin fashion
        for (int i = 0; i < ranges.length; i++) {
          OffsetRange range = ranges[i];
          if (!exhaustedPartitions.contains(range.partition())) {
            long toOffsetMax = toOffsetMap.get(range.topicPartition());
            long toOffset = Math.min(toOffsetMax, range.untilOffset() + eventsPerPartition);
            if (toOffset == toOffsetMax) {
              exhaustedPartitions.add(range.partition());
            }
            allocedEvents += toOffset - range.untilOffset();
            // We need recompute toOffset if allocedEvents larger than numEvents.
            if (allocedEvents > numEvents) {
              long offsetsToAdd = Math.min(eventsPerPartition, (numEvents - allocedEvents));
              toOffset = Math.min(toOffsetMax, toOffset + offsetsToAdd);
            }
            ranges[i] = OffsetRange.create(range.topicPartition(), range.fromOffset(), toOffset);
          }
        }
      }

      return ranges;
    }

    public static long totalNewMessages(OffsetRange[] ranges) {
      return Arrays.stream(ranges).mapToLong(OffsetRange::count).sum();
    }
  }

  /**
   * Helpers to deal with tricky scala <=> java conversions. (oh my!)
   */
  static class ScalaHelpers {

//    public static <K, V> Map<K, V> toScalaMap(HashMap<K, V> m) {
//      return JavaConverters.mapAsScalaMapConverter(m).asScala().toMap(Predef.conforms());
//    }
//
//    public static Set<String> toScalaSet(HashSet<String> s) {
//      return JavaConverters.asScalaSetConverter(s).asScala().toSet();
//    }

//    public static <K, V> java.util.Map<K, V> toJavaMap(Map<K, V> m) {
//      return JavaConverters.mapAsJavaMapConverter(m).asJava();
//    }
  }

  /**
   * Kafka reset offset strategies
   */
  enum KafkaResetOffsetStrategies {
    LARGEST, SMALLEST
  }

  /**
   * Configs to be passed for this source. All standard Kafka consumer configs are also respected
   */
  static class Config {

    private static final String KAFKA_TOPIC_NAME = "hoodie.deltastreamer.source.kafka.topic";
    private static final KafkaResetOffsetStrategies DEFAULT_AUTO_RESET_OFFSET = KafkaResetOffsetStrategies.LARGEST;
  }

  private final HashMap<String, String> kafkaParams;
  private final TypedProperties props;
  protected final String topicName;

  public KafkaOffsetGen(TypedProperties props) {
    this.props = props;
    kafkaParams = new HashMap<String, String>();
    for (Object prop : props.keySet()) {
      kafkaParams.put(prop.toString(), props.getString(prop.toString()));
    }
    DataSourceUtils.checkRequiredProperties(props, Collections.singletonList(Config.KAFKA_TOPIC_NAME));
    topicName = props.getString(Config.KAFKA_TOPIC_NAME);
  }

  public OffsetRange[] getNextOffsetRanges(Option<String> lastCheckpointStr, long sourceLimit) {

    // Obtain current metadata for the topic
    // KafkaCluster cluster = new KafkaCluster(ScalaHelpers.toScalaMap(kafkaParams));
    KafkaConsumer cluster = new KafkaConsumer(kafkaParams);
    // Either<ArrayBuffer<Throwable>, Set<TopicPartition>> either =
    //     cluster.getPartitions(ScalaHelpers.toScalaSet(new HashSet<>(Collections.singletonList(topicName))));

    List<PartitionInfo> partitionInfo = cluster.partitionsFor(topicName);

    // if (either.isLeft()) {
    // log errors. and bail out.
    //   throw new HoodieDeltaStreamerException("Error obtaining partition metadata", either.left().get().head());
    // }
    // Set<TopicPartition> topicPartitions = either.right().get();
    Set<TopicPartition> topicPartitions = partitionInfo.stream()
            .map(x -> new TopicPartition(x.topic(), x.partition())).collect(Collectors.toSet());

    // Determine the offset ranges to read from
    HashMap<TopicPartition, Long> fromOffsets;
    HashMap<TopicPartition, Long> checkpointOffsets;
    if (lastCheckpointStr.isPresent()) {
      fromOffsets = checkupValidOffsets(cluster, lastCheckpointStr, topicPartitions);
    } else {
      KafkaResetOffsetStrategies autoResetValue = KafkaResetOffsetStrategies
              .valueOf(props.getString("auto.offset.reset", Config.DEFAULT_AUTO_RESET_OFFSET.toString()).toUpperCase());
      switch (autoResetValue) {
        case SMALLEST:
          fromOffsets = (HashMap<TopicPartition, Long>) cluster.beginningOffsets(topicPartitions);
                  // new HashMap(ScalaHelpers.toJavaMap(cluster.getEarliestLeaderOffsets(topicPartitions).right().get()));
          break;
        case LARGEST:
          fromOffsets = (HashMap<TopicPartition, Long>) cluster.endOffsets(topicPartitions);
                  // new HashMap(ScalaHelpers.toJavaMap(cluster.getLatestLeaderOffsets(topicPartitions).right().get()));
          break;
        default:
          throw new HoodieNotSupportedException("Auto reset value must be one of 'smallest' or 'largest' ");
      }
    }

    // Obtain the latest offsets.
    HashMap<TopicPartition, Long> toOffsets = (HashMap<TopicPartition, Long>) cluster.endOffsets(topicPartitions);
    //    new HashMap(ScalaHelpers.toJavaMap(cluster.getLatestLeaderOffsets(topicPartitions).right().get()));

    // Come up with final set of OffsetRanges to read (account for new partitions, limit number of events)
    long numEvents = Math.min(DEFAULT_MAX_EVENTS_TO_READ, sourceLimit);
    OffsetRange[] offsetRanges = CheckpointUtils.computeOffsetRanges(fromOffsets, toOffsets, numEvents);

    return offsetRanges;
  }

  // check up checkpoint offsets is valid or not, if true, return checkpoint offsets,
  // else return earliest offsets
  private HashMap<TopicPartition, Long> checkupValidOffsets(KafkaConsumer cluster,
                                                            Option<String> lastCheckpointStr, Set<TopicPartition> topicPartitions) {
    HashMap<TopicPartition, Long> checkpointOffsets =
            CheckpointUtils.strToOffsets(lastCheckpointStr.get());
    HashMap<TopicPartition, Long> earliestOffsets = (HashMap<TopicPartition, Long>) cluster.beginningOffsets(topicPartitions);
            // new HashMap(ScalaHelpers.toJavaMap(cluster.getEarliestLeaderOffsets(topicPartitions).right().get()));

    boolean checkpointOffsetReseter = checkpointOffsets.entrySet().stream()
            .anyMatch(offset -> offset.getValue() < earliestOffsets.get(offset.getKey()));
    return checkpointOffsetReseter ? earliestOffsets : checkpointOffsets;
  }


  public String getTopicName() {
    return topicName;
  }

  public HashMap<String, Object> getKafkaParams() {
    HashMap<String, Object> res = new HashMap<>();
    for (Map.Entry<String, String> entry : kafkaParams.entrySet()) {
      res.put(entry.getKey(), entry.getValue());
    }
    return res;
  }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.streams.KeyQueryMetadata;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsMetadata;
import org.apache.kafka.streams.TopologyWrapper;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.processor.StreamPartitioner;
import org.apache.kafka.streams.processor.internals.testutil.DummyStreamsConfig;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.internals.StreamsMetadataImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.kafka.common.utils.Utils.mkSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreamsMetadataStateTest {

    private StreamsMetadataState metadataState;
    private HostInfo hostOne;
    private HostInfo hostTwo;
    private HostInfo hostThree;
    private TopicPartition topic1P0;
    private TopicPartition topic2P0;
    private TopicPartition topic3P0;
    private Map<HostInfo, Set<TopicPartition>> hostToActivePartitions;
    private Map<HostInfo, Set<TopicPartition>> hostToStandbyPartitions;
    private StreamsBuilder builder;
    private TopicPartition topic1P1;
    private TopicPartition topic2P1;
    private TopicPartition topic4P0;
    private Map<TopicPartition, PartitionInfo> partitionInfos;
    private final String globalTable = "global-table";
    private final LogContext logContext = new LogContext(String.format("test [%s] ", "StreamsMetadataStateTest"));
    private StreamPartitioner<String, Object> partitioner;
    private Set<String> storeNames;

    @BeforeEach
    public void before() {
        builder = new StreamsBuilder();
        final KStream<Object, Object> one = builder.stream("topic-one");
        one.groupByKey().count(Materialized.as("table-one"));

        final KStream<Object, Object> two = builder.stream("topic-two");
        two.groupByKey().count(Materialized.as("table-two"));

        builder.stream("topic-three")
                .groupByKey()
                .count(Materialized.as("table-three"));

        one.merge(two).groupByKey().count(Materialized.as("merged-table"));

        builder.stream("topic-four").mapValues(value -> value);

        builder.globalTable("global-topic",
                            Consumed.with(null, null),
                            Materialized.as(globalTable));

        TopologyWrapper.getInternalTopologyBuilder(builder.build()).setApplicationId("appId");

        topic1P0 = new TopicPartition("topic-one", 0);
        topic1P1 = new TopicPartition("topic-one", 1);
        topic2P0 = new TopicPartition("topic-two", 0);
        topic2P1 = new TopicPartition("topic-two", 1);
        topic3P0 = new TopicPartition("topic-three", 0);
        topic4P0 = new TopicPartition("topic-four", 0);

        hostOne = new HostInfo("host-one", 8080);
        hostTwo = new HostInfo("host-two", 9090);
        hostThree = new HostInfo("host-three", 7070);
        hostToActivePartitions = new HashMap<>();
        hostToActivePartitions.put(hostOne, mkSet(topic1P0, topic2P1, topic4P0));
        hostToActivePartitions.put(hostTwo, mkSet(topic2P0, topic1P1));
        hostToActivePartitions.put(hostThree, Collections.singleton(topic3P0));
        hostToStandbyPartitions = new HashMap<>();
        hostToStandbyPartitions.put(hostThree, mkSet(topic1P0, topic2P1, topic4P0));
        hostToStandbyPartitions.put(hostOne, mkSet(topic2P0, topic1P1));
        hostToStandbyPartitions.put(hostTwo, Collections.singleton(topic3P0));

        partitionInfos = new HashMap<>();
        partitionInfos.put(new TopicPartition("topic-one", 0), new PartitionInfo("topic-one", 0, null, null, null));
        partitionInfos.put(new TopicPartition("topic-one", 1), new PartitionInfo("topic-one", 1, null, null, null));
        partitionInfos.put(new TopicPartition("topic-two", 0), new PartitionInfo("topic-two", 0, null, null, null));
        partitionInfos.put(new TopicPartition("topic-two", 1), new PartitionInfo("topic-two", 1, null, null, null));
        partitionInfos.put(new TopicPartition("topic-three", 0), new PartitionInfo("topic-three", 0, null, null, null));
        partitionInfos.put(new TopicPartition("topic-four", 0), new PartitionInfo("topic-four", 0, null, null, null));

        final TopologyMetadata topologyMetadata = new TopologyMetadata(TopologyWrapper.getInternalTopologyBuilder(builder.build()), new DummyStreamsConfig());
        topologyMetadata.buildAndRewriteTopology();
        metadataState = new StreamsMetadataState(topologyMetadata, hostOne, logContext);
        metadataState.onChange(hostToActivePartitions, hostToStandbyPartitions, partitionInfos);
        partitioner = (topic, key, value, numPartitions) -> 1;
        storeNames = mkSet("table-one", "table-two", "merged-table", globalTable);
    }

    static class MultiValuedPartitioner implements StreamPartitioner<String, Object> {

        @Override
        @Deprecated
        public Integer partition(final String topic, final String key, final Object value, final int numPartitions) {
            return null;
        }

        @Override
        public Optional<Set<Integer>> partitions(final String topic, final String key, final Object value, final int numPartitions) {
            final Set<Integer> partitions = new HashSet<>();
            partitions.add(0);
            partitions.add(1);
            return Optional.of(partitions);
        }
    }

    @Test
    public void shouldNotThrowExceptionWhenOnChangeNotCalled() {
        final Collection<StreamsMetadata> metadata = new StreamsMetadataState(
            new TopologyMetadata(TopologyWrapper.getInternalTopologyBuilder(builder.build()), new DummyStreamsConfig()),
            hostOne,
            logContext
        ).allMetadataForStore("store");
        assertEquals(0, metadata.size());
    }

    @Test
    public void shouldGetAllStreamInstances() {
        final StreamsMetadata one = new StreamsMetadataImpl(hostOne,
            mkSet(globalTable, "table-one", "table-two", "merged-table"),
            mkSet(topic1P0, topic2P1, topic4P0),
            mkSet("table-one", "table-two", "merged-table"),
            mkSet(topic2P0, topic1P1));
        final StreamsMetadata two = new StreamsMetadataImpl(hostTwo,
            mkSet(globalTable, "table-two", "table-one", "merged-table"),
            mkSet(topic2P0, topic1P1),
            mkSet("table-three"),
            mkSet(topic3P0));
        final StreamsMetadata three = new StreamsMetadataImpl(hostThree,
            mkSet(globalTable, "table-three"),
            Collections.singleton(topic3P0),
            mkSet("table-one", "table-two", "merged-table"),
            mkSet(topic1P0, topic2P1, topic4P0));

        final Collection<StreamsMetadata> actual = metadataState.allMetadata();
        assertEquals(3, actual.size());
        assertTrue(actual.contains(one), "expected " + actual + " to contain " + one);
        assertTrue(actual.contains(two), "expected " + actual + " to contain " + two);
        assertTrue(actual.contains(three), "expected " + actual + " to contain " + three);
    }

    @Test
    public void shouldGetAllStreamsInstancesWithNoStores() {
        builder.stream("topic-five").filter((key, value) -> true).to("some-other-topic");

        final TopicPartition tp5 = new TopicPartition("topic-five", 1);
        final HostInfo hostFour = new HostInfo("host-four", 8080);
        hostToActivePartitions.put(hostFour, mkSet(tp5));

        metadataState.onChange(hostToActivePartitions, Collections.emptyMap(),
            Collections.singletonMap(tp5, new PartitionInfo("topic-five", 1, null, null, null)));

        final StreamsMetadata expected = new StreamsMetadataImpl(hostFour, Collections.singleton(globalTable),
                Collections.singleton(tp5), Collections.emptySet(), Collections.emptySet());
        final Collection<StreamsMetadata> actual = metadataState.allMetadata();
        assertTrue(actual.contains(expected), "expected " + actual + " to contain " + expected);
    }

    @Test
    public void shouldGetInstancesForStoreName() {
        final StreamsMetadata one = new StreamsMetadataImpl(hostOne,
            mkSet(globalTable, "table-one", "table-two", "merged-table"),
            mkSet(topic1P0, topic2P1, topic4P0),
            mkSet("table-one", "table-two", "merged-table"),
            mkSet(topic2P0, topic1P1));
        final StreamsMetadata two = new StreamsMetadataImpl(hostTwo,
            mkSet(globalTable, "table-two", "table-one", "merged-table"),
            mkSet(topic2P0, topic1P1),
            mkSet("table-three"),
            mkSet(topic3P0));
        final Collection<StreamsMetadata> actual = metadataState.allMetadataForStore("table-one");
        final Map<HostInfo, StreamsMetadata> actualAsMap = actual.stream()
            .collect(Collectors.toMap(StreamsMetadata::hostInfo, Function.identity()));
        assertEquals(3, actual.size());
        assertTrue(actual.contains(one), "expected " + actual + " to contain " + one);
        assertTrue(actual.contains(two), "expected " + actual + " to contain " + two);
        assertTrue(actualAsMap.get(hostThree).standbyStateStoreNames().contains("table-one"),
            "expected " + hostThree + " to contain as standby");
    }

    @Test
    public void shouldThrowIfStoreNameIsNullOnGetAllInstancesWithStore() {
        assertThrows(NullPointerException.class, () -> metadataState.allMetadataForStore(null));
    }

    @Test
    public void shouldReturnEmptyCollectionOnGetAllInstancesWithStoreWhenStoreDoesntExist() {
        final Collection<StreamsMetadata> actual = metadataState.allMetadataForStore("not-a-store");
        assertTrue(actual.isEmpty());
    }

    @Test
    public void shouldGetInstanceWithKey() {
        final TopicPartition tp4 = new TopicPartition("topic-three", 1);
        hostToActivePartitions.put(hostTwo, mkSet(topic2P0, tp4));

        metadataState.onChange(hostToActivePartitions, hostToStandbyPartitions,
            Collections.singletonMap(tp4, new PartitionInfo("topic-three", 1, null, null, null)));

        final KeyQueryMetadata expected = new KeyQueryMetadata(hostThree, mkSet(hostTwo), 0);
        final KeyQueryMetadata actual = metadataState.keyQueryMetadataForKey("table-three",
                                                                    "the-key",
                                                                    Serdes.String().serializer());
        assertEquals(expected, actual);
    }

    @Test
    public void shouldGetInstanceWithKeyAndCustomPartitioner() {
        final TopicPartition tp4 = new TopicPartition("topic-three", 1);
        hostToActivePartitions.put(hostTwo, mkSet(topic2P0, tp4));

        metadataState.onChange(hostToActivePartitions, hostToStandbyPartitions,
            Collections.singletonMap(tp4, new PartitionInfo("topic-three", 1, null, null, null)));

        final KeyQueryMetadata expected = new KeyQueryMetadata(hostTwo, Collections.emptySet(), 1);

        final KeyQueryMetadata actual = metadataState.keyQueryMetadataForKey("table-three",
                "the-key",
                partitioner);
        assertEquals(expected, actual);
        assertEquals(1, actual.partition());
    }

    @Test
    public void shouldFailWhenIqQueriedWithCustomPartitionerReturningMultiplePartitions() {
        final TopicPartition tp4 = new TopicPartition("topic-three", 1);
        hostToActivePartitions.put(hostTwo, mkSet(topic2P0, tp4));

        metadataState.onChange(hostToActivePartitions, hostToStandbyPartitions,
                Collections.singletonMap(tp4, new PartitionInfo("topic-three", 1, null, null, null)));


        assertThrows(IllegalArgumentException.class, () -> metadataState.keyQueryMetadataForKey("table-three",
                "the-key",
                new MultiValuedPartitioner()));
    }

    @Test
    public void shouldReturnNotAvailableWhenClusterIsEmpty() {
        metadataState.onChange(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        final KeyQueryMetadata result = metadataState.keyQueryMetadataForKey("table-one", "a", Serdes.String().serializer());
        assertEquals(KeyQueryMetadata.NOT_AVAILABLE, result);
    }

    @Test
    public void shouldGetInstanceWithKeyWithMergedStreams() {
        final TopicPartition topic2P2 = new TopicPartition("topic-two", 2);
        hostToActivePartitions.put(hostTwo, mkSet(topic2P0, topic1P1, topic2P2));
        hostToStandbyPartitions.put(hostOne, mkSet(topic2P0, topic1P1, topic2P2));
        metadataState.onChange(hostToActivePartitions, hostToStandbyPartitions,
                Collections.singletonMap(topic2P2, new PartitionInfo("topic-two", 2, null, null, null)));

        final KeyQueryMetadata expected = new KeyQueryMetadata(hostTwo, mkSet(hostOne), 2);

        final KeyQueryMetadata actual = metadataState.keyQueryMetadataForKey("merged-table",  "the-key",
            (topic, key, value, numPartitions) -> 2);

        assertEquals(expected, actual);
    }

    @Test
    public void shouldReturnNullOnGetWithKeyWhenStoreDoesntExist() {
        final KeyQueryMetadata actual = metadataState.keyQueryMetadataForKey("not-a-store",
                "key",
                Serdes.String().serializer());
        assertNull(actual);
    }

    @Test
    public void shouldThrowWhenKeyIsNull() {
        assertThrows(NullPointerException.class, () -> metadataState.keyQueryMetadataForKey("table-three", null, Serdes.String().serializer()));
    }

    @Test
    public void shouldThrowWhenSerializerIsNull() {
        assertThrows(NullPointerException.class, () -> metadataState.keyQueryMetadataForKey("table-three", "key", (Serializer<Object>) null));
    }

    @Test
    public void shouldThrowIfStoreNameIsNull() {
        assertThrows(NullPointerException.class, () -> metadataState.keyQueryMetadataForKey(null, "key", Serdes.String().serializer()));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldThrowIfStreamPartitionerIsNull() {
        assertThrows(NullPointerException.class, () -> metadataState.keyQueryMetadataForKey(null, "key", (StreamPartitioner) null));
    }

    @Test
    public void shouldHaveGlobalStoreInAllMetadata() {
        final Collection<StreamsMetadata> metadata = metadataState.allMetadataForStore(globalTable);
        assertEquals(3, metadata.size());
        for (final StreamsMetadata streamsMetadata : metadata) {
            assertTrue(streamsMetadata.stateStoreNames().contains(globalTable));
        }
    }

    @Test
    public void shouldGetLocalMetadataWithRightActiveStandbyInfo() {
        assertEquals(hostOne, metadataState.getLocalMetadata().hostInfo());
        assertEquals(hostToActivePartitions.get(hostOne), metadataState.getLocalMetadata().topicPartitions());
        assertEquals(hostToStandbyPartitions.get(hostOne), metadataState.getLocalMetadata().standbyTopicPartitions());
        assertEquals(storeNames, metadataState.getLocalMetadata().stateStoreNames());
        assertEquals(storeNames.stream().filter(s -> !s.equals(globalTable)).collect(Collectors.toSet()),
            metadataState.getLocalMetadata().standbyStateStoreNames());
    }

    @Test
    public void shouldGetQueryMetadataForGlobalStoreWithKey() {
        final KeyQueryMetadata metadata = metadataState.keyQueryMetadataForKey(globalTable, "key", Serdes.String().serializer());
        assertEquals(hostOne, metadata.activeHost());
        assertTrue(metadata.standbyHosts().isEmpty());
    }

    @Test
    public void shouldGetAnyHostForGlobalStoreByKeyIfMyHostUnknown() {
        final StreamsMetadataState streamsMetadataState = new StreamsMetadataState(
            new TopologyMetadata(TopologyWrapper.getInternalTopologyBuilder(builder.build()), new DummyStreamsConfig()),
            StreamsMetadataState.UNKNOWN_HOST,
            logContext
        );
        streamsMetadataState.onChange(hostToActivePartitions, hostToStandbyPartitions, partitionInfos);
        assertNotNull(streamsMetadataState.keyQueryMetadataForKey(globalTable, "key", Serdes.String().serializer()));
    }

    @Test
    public void shouldGetQueryMetadataForGlobalStoreWithKeyAndPartitioner() {
        final KeyQueryMetadata metadata = metadataState.keyQueryMetadataForKey(globalTable, "key", partitioner);
        assertEquals(hostOne, metadata.activeHost());
        assertTrue(metadata.standbyHosts().isEmpty());
    }

    @Test
    public void shouldGetAnyHostForGlobalStoreByKeyAndPartitionerIfMyHostUnknown() {
        final StreamsMetadataState streamsMetadataState = new StreamsMetadataState(
            new TopologyMetadata(TopologyWrapper.getInternalTopologyBuilder(builder.build()), new DummyStreamsConfig()),
            StreamsMetadataState.UNKNOWN_HOST,
            logContext
        );
        streamsMetadataState.onChange(hostToActivePartitions, hostToStandbyPartitions, partitionInfos);
        assertNotNull(streamsMetadataState.keyQueryMetadataForKey(globalTable, "key", partitioner));
    }

    @Test
    public void shouldReturnAllMetadataThatRemainsValidAfterChange() {
        final Collection<StreamsMetadata> allMetadata = metadataState.allMetadata();
        final Collection<StreamsMetadata> copy = new ArrayList<>(allMetadata);
        assertFalse(allMetadata.isEmpty(), "invalid test");
        metadataState.onChange(Collections.emptyMap(), Collections.emptyMap(), partitionInfos);
        assertEquals(allMetadata, copy, "encapsulation broken");
    }

    @Test
    public void shouldNotReturnMutableReferenceToInternalAllMetadataCollection() {
        final Collection<StreamsMetadata> allMetadata = metadataState.allMetadata();
        assertFalse(allMetadata.isEmpty(), "invalid test");

        try {
            // Either this should not affect internal state of 'metadataState'
            allMetadata.clear();
        } catch (final UnsupportedOperationException e) {
            // Or should fail.
        }

        assertFalse(metadataState.allMetadata().isEmpty(), "encapsulation broken");
    }
}

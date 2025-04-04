/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.cassandra;

import static io.debezium.connector.cassandra.utils.TestUtils.deleteTestKeyspaceTables;
import static io.debezium.connector.cassandra.utils.TestUtils.deleteTestOffsets;
import static io.debezium.connector.cassandra.utils.TestUtils.keyspaceTable;
import static io.debezium.connector.cassandra.utils.TestUtils.propertiesForContext;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import io.debezium.config.Configuration;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.connector.cassandra.spi.ProvidersResolver;
import io.debezium.connector.cassandra.utils.TestUtils;

public class SnapshotProcessorTest extends CassandraConnectorTestBase {

    @After
    public void afterTest() {
        if (context != null) {
            context.cleanUp();
        }
    }

    @Before
    public void beforeTest() {
        provider = ProvidersResolver.resolveConnectorContextProvider();
    }

    @Test
    public void testSnapshotTable() throws Throwable {
        context = provider.provideContext(Configuration.from(TestUtils.generateDefaultConfigMap()));
        SnapshotProcessor snapshotProcessor = Mockito.spy(new SnapshotProcessor(context, context.getClusterName()));
        when(snapshotProcessor.isRunning()).thenReturn(true);

        int tableSize = 5;
        context.getCassandraClient().execute("CREATE TABLE IF NOT EXISTS " + keyspaceTable("cdc_table") + " (a int, b text, PRIMARY KEY(a)) WITH cdc = true;");
        context.getCassandraClient().execute("CREATE TABLE IF NOT EXISTS " + keyspaceTable("cdc_table2") + " (a int, b text, PRIMARY KEY(a)) WITH cdc = true;");

        for (int i = 0; i < tableSize; i++) {
            context.getCassandraClient().execute("INSERT INTO " + keyspaceTable("cdc_table") + "(a, b) VALUES (?, ?)", i, String.valueOf(i));
            context.getCassandraClient().execute("INSERT INTO " + keyspaceTable("cdc_table2") + "(a, b) VALUES (?, ?)", i + 10, String.valueOf(i + 10));
        }

        ChangeEventQueue<Event> queue = context.getQueues().get(0);
        assertEquals(queue.totalCapacity(), queue.remainingCapacity());
        snapshotProcessor.process();
        assertEquals(2 * tableSize, queue.totalCapacity() - queue.remainingCapacity());
        final List<ChangeRecord> table1 = new ArrayList<>();
        final List<ChangeRecord> table2 = new ArrayList<>();
        for (Event event : queue.poll()) {
            ChangeRecord record = (ChangeRecord) event;
            Assert.assertEquals(record.getEventType(), Event.EventType.CHANGE_EVENT);
            Assert.assertEquals(record.getOp(), Record.Operation.INSERT);
            Assert.assertEquals(record.getSource().cluster, CLUSTER_NAME);
            assertTrue(record.getSource().snapshot);
            final String tableName = record.getSource().keyspaceTable.name();
            if (tableName.equals(keyspaceTable("cdc_table"))) {
                table1.add(record);
            }
            else {
                table2.add(record);
            }
            Assert.assertEquals(record.getSource().offsetPosition, OffsetPosition.defaultOffsetPosition());
        }
        assertEquals(tableSize, table1.size());
        assertEquals(tableSize, table2.size());
        deleteTestKeyspaceTables();
        deleteTestOffsets(context);
    }

    @Test
    public void testSnapshotSkipsNonCdcEnabledTable() throws Throwable {
        context = provider.provideContext(Configuration.from(TestUtils.generateDefaultConfigMap()));
        SnapshotProcessor snapshotProcessor = Mockito.spy(new SnapshotProcessor(context, context.getClusterName()));
        when(snapshotProcessor.isRunning()).thenReturn(true);

        int tableSize = 5;
        context.getCassandraClient().execute("CREATE TABLE IF NOT EXISTS " + keyspaceTable("non_cdc_table") + " (a int, b text, PRIMARY KEY(a)) WITH cdc = false;");
        for (int i = 0; i < tableSize; i++) {
            context.getCassandraClient().execute("INSERT INTO " + keyspaceTable("non_cdc_table") + "(a, b) VALUES (?, ?)", i, String.valueOf(i));
        }

        ChangeEventQueue<Event> queue = context.getQueues().get(0);
        assertEquals(queue.totalCapacity(), queue.remainingCapacity());
        snapshotProcessor.process();
        assertEquals(queue.totalCapacity(), queue.remainingCapacity());

        deleteTestKeyspaceTables();
        deleteTestOffsets(context);
    }

    @Test
    public void testSnapshotEmptyTable() throws Throwable {
        context = provider.provideContext(Configuration.from(TestUtils.generateDefaultConfigMap()));
        AtomicBoolean globalTaskState = new AtomicBoolean(true);
        SnapshotProcessor snapshotProcessor = Mockito.spy(new SnapshotProcessor(context, context.getClusterName()));
        when(snapshotProcessor.isRunning()).thenReturn(true);

        context.getCassandraClient().execute("CREATE TABLE IF NOT EXISTS " + keyspaceTable("cdc_table") + " (a int, b text, PRIMARY KEY(a)) WITH cdc = true;");

        ChangeEventQueue<Event> queue = context.getQueues().get(0);
        assertEquals(queue.totalCapacity(), queue.remainingCapacity());
        snapshotProcessor.process(); // records empty table to snapshot.offset, so it won't be snapshotted again
        assertEquals(queue.totalCapacity(), queue.remainingCapacity());

        int tableSize = 5;
        for (int i = 0; i < tableSize; i++) {
            context.getCassandraClient().execute("INSERT INTO " + keyspaceTable("cdc_table") + "(a, b) VALUES (?, ?)", i, String.valueOf(i));
        }
        snapshotProcessor.process();
        assertEquals(queue.totalCapacity(), queue.remainingCapacity()); // newly inserted records should be processed by commit log processor instead

        deleteTestKeyspaceTables();
        deleteTestOffsets(context);
        globalTaskState.set(false);
    }

    @Test
    public void testSnapshotModeAlways() throws Throwable {
        Map<String, Object> configs = propertiesForContext();
        configs.put(CassandraConnectorConfig.KAFKA_PRODUCER_CONFIG_PREFIX + ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, TestUtils.TEST_KAFKA_SERVERS);
        configs.put(CassandraConnectorConfig.SNAPSHOT_MODE.name(), "always");
        configs.put(CassandraConnectorConfig.SNAPSHOT_POLL_INTERVAL_MS.name(), "0");

        context = provider.provideContext(Configuration.from(configs));

        SnapshotProcessor snapshotProcessorSpy = Mockito.spy(new SnapshotProcessor(context, context.getClusterName()));
        doNothing().when(snapshotProcessorSpy).snapshot();

        for (int i = 0; i < 5; i++) {
            snapshotProcessorSpy.process();
        }
        verify(snapshotProcessorSpy, times(5)).snapshot();
    }

    @Test
    public void testSnapshotModeInitial() throws Throwable {
        Map<String, Object> configs = propertiesForContext();
        configs.put(CassandraConnectorConfig.SNAPSHOT_MODE.name(), "initial");
        configs.put(CassandraConnectorConfig.SNAPSHOT_POLL_INTERVAL_MS.name(), "0");
        context = provider.provideContext(Configuration.from(configs));
        SnapshotProcessor snapshotProcessorSpy = Mockito.spy(new SnapshotProcessor(context, context.getClusterName()));
        doNothing().when(snapshotProcessorSpy).snapshot();

        for (int i = 0; i < 5; i++) {
            snapshotProcessorSpy.process();
        }
        verify(snapshotProcessorSpy, times(1)).snapshot();
    }

    @Test
    public void testSnapshotModeNever() throws Throwable {
        Map<String, Object> configs = propertiesForContext();
        configs.put(CassandraConnectorConfig.SNAPSHOT_MODE.name(), "never");
        configs.put(CassandraConnectorConfig.SNAPSHOT_POLL_INTERVAL_MS.name(), "0");
        context = provider.provideContext(Configuration.from(configs));
        SnapshotProcessor snapshotProcessorSpy = Mockito.spy(new SnapshotProcessor(context, context.getClusterName()));
        doNothing().when(snapshotProcessorSpy).snapshot();

        for (int i = 0; i < 5; i++) {
            snapshotProcessorSpy.process();
        }
        verify(snapshotProcessorSpy, never()).snapshot();
    }
}

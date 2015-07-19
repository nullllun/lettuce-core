package com.lambdaworks.redis.cluster;

import static com.google.code.tempusfugit.temporal.Duration.seconds;
import static com.google.code.tempusfugit.temporal.Timeout.timeout;
import static com.lambdaworks.redis.cluster.ClusterTestUtil.getNodeId;
import static com.lambdaworks.redis.cluster.ClusterTestUtil.getOwnPartition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import com.google.code.tempusfugit.temporal.Condition;
import com.google.code.tempusfugit.temporal.WaitFor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.lambdaworks.redis.FastShutdown;
import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.RedisClusterConnection;
import com.lambdaworks.redis.RedisFuture;
import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.TestSettings;
import com.lambdaworks.redis.cluster.models.partitions.ClusterPartitionParser;
import com.lambdaworks.redis.cluster.models.partitions.Partitions;
import com.lambdaworks.redis.cluster.models.partitions.RedisClusterNode;

/**
 * @author <a href="mailto:mpaluch@paluch.biz">Mark Paluch</a>
 * @since 3.0
 */
@SuppressWarnings("unchecked")
public class RedisClusterSetupTest {
    public static final String host = TestSettings.hostAddr();
    public static final int port1 = 7383;
    public static final int port2 = 7384;

    private static RedisClusterClient clusterClient;
    private static RedisClient client1;
    private static RedisClient client2;

    private RedisClusterConnection<String, String> redis1;
    private RedisClusterConnection<String, String> redis2;

    @Rule
    public ClusterRule clusterRule = new ClusterRule(clusterClient, port1, port2);

    @BeforeClass
    public static void setupClient() {
        clusterClient = new RedisClusterClient(RedisURI.Builder.redis(host, port1).build());
        client1 = new RedisClient(host, port1);
        client2 = new RedisClient(host, port2);
    }

    @AfterClass
    public static void shutdownClient() {
        FastShutdown.shutdown(clusterClient);
        FastShutdown.shutdown(client1);
        FastShutdown.shutdown(client2);
    }

    @Before
    public void openConnection() throws Exception {
        redis1 = client1.connect();
        redis2 = client2.connect();
        clusterRule.clusterReset();
    }

    @After
    public void closeConnection() throws Exception {
        redis1.close();
        redis2.close();
    }

    @Test
    public void clusterMeet() throws Exception {

        Partitions partitionsBeforeMeet = ClusterPartitionParser.parse(redis1.clusterNodes());
        assertThat(partitionsBeforeMeet.getPartitions()).hasSize(1);

        String result = redis1.clusterMeet(host, port2);

        assertThat(result).isEqualTo("OK");
        waitForCluster(redis1);

        Partitions partitionsAfterMeet = ClusterPartitionParser.parse(redis1.clusterNodes());
        assertThat(partitionsAfterMeet.getPartitions()).hasSize(2);
    }

    @Test
    public void clusterForget() throws Exception {

        String result = redis1.clusterMeet(host, port2);

        assertThat(result).isEqualTo("OK");
        waitForCluster(redis1);

        WaitFor.waitOrTimeout(new Condition() {
            @Override
            public boolean isSatisfied() {
                Partitions partitions = ClusterPartitionParser.parse(redis1.clusterNodes());
                for (RedisClusterNode redisClusterNode : partitions.getPartitions()) {
                    if (redisClusterNode.getFlags().contains(RedisClusterNode.NodeFlag.HANDSHAKE)) {
                        return false;
                    }
                }
                return true;
            }
        }, timeout(seconds(5)));

        Partitions partitions = ClusterPartitionParser.parse(redis1.clusterNodes());
        for (RedisClusterNode redisClusterNode : partitions.getPartitions()) {
            if (!redisClusterNode.getFlags().contains(RedisClusterNode.NodeFlag.MYSELF)) {
                redis1.clusterForget(redisClusterNode.getNodeId());
            }
        }

        Partitions partitionsAfterForget = ClusterPartitionParser.parse(redis1.clusterNodes());
        assertThat(partitionsAfterForget.getPartitions()).hasSize(1);

    }

    private void waitForCluster(final RedisClusterConnection<String, String> connection) throws InterruptedException,
            TimeoutException {
        WaitFor.waitOrTimeout(new Condition() {
            @Override
            public boolean isSatisfied() {
                Partitions partitionsAfterMeet = ClusterPartitionParser.parse(connection.clusterNodes());
                return partitionsAfterMeet.getPartitions().size() == 2;
            }
        }, timeout(seconds(5)));
    }

    @Test
    public void clusterAddDelSlots() throws Exception {

        redis1.clusterMeet(host, port2);
        waitForCluster(redis1);
        waitForCluster(redis2);

        add6SlotsEach();

        waitForSlots(redis1, 6);
        waitForSlots(redis2, 6);

        final Set<Integer> set1 = ImmutableSet.of(1, 2, 3, 4, 5, 6);
        final Set<Integer> set2 = ImmutableSet.of(7, 8, 9, 10, 11, 12);

        deleteSlots(redis1, set1);
        deleteSlots(redis2, set2);

        verifyDeleteSlots(redis1, set1);
        verifyDeleteSlots(redis2, set2);
    }

    @Test
    public void clusterTopologyRefresh() throws Exception {

        clusterClient.setOptions(new ClusterClientOptions.Builder().refreshClusterView(true).refreshPeriod(5, TimeUnit.SECONDS)
                .build());
        RedisAdvancedClusterAsyncConnection<String, String> clusterConnection = clusterClient.connectClusterAsync();

        assertThat(clusterClient.getPartitions()).hasSize(1);

        setup2Masters();

        assertThat(clusterClient.getPartitions()).hasSize(2);

        clusterConnection.close();

    }

    protected void setup2Masters() throws InterruptedException, TimeoutException {
        redis1.clusterMeet(host, port2);
        waitForCluster(redis1);
        waitForCluster(redis2);

        for (int i = 0; i < 12000; i += 5) {
            redis1.clusterAddSlots(i, i + 1, i + 2, i + 3, i + 4);
        }
        for (int i = 12000; i < 16384; i += 4) {
            redis2.clusterAddSlots(i, i + 1, i + 2, i + 3);
        }

        waitForSlots(redis1, 12000);
        waitForSlots(redis2, 4384);

        WaitFor.waitOrTimeout(new Condition() {
            @Override
            public boolean isSatisfied() {
                clusterClient.reloadPartitions();
                if (clusterClient.getPartitions().size() == 2) {
                    for (RedisClusterNode redisClusterNode : clusterClient.getPartitions()) {
                        if (redisClusterNode.getSlots().size() < 4381) {
                            return false;
                        }
                    }

                    if (!redis1.clusterInfo().contains("cluster_state:ok")) {
                        return false;
                    }

                    if (!redis2.clusterInfo().contains("cluster_state:ok")) {
                        return false;
                    }
                    return true;
                }

                return false;
            }
        }, timeout(seconds(6)));
    }

    @Test
    public void changeTopologyWhileOperations() throws Exception {

        clusterClient.setOptions(new ClusterClientOptions.Builder().refreshClusterView(true).refreshPeriod(1, TimeUnit.SECONDS)
                .build());
        RedisAdvancedClusterAsyncConnection<String, String> clusterConnection = clusterClient.connectClusterAsync();

        setup2Masters();

        assertExecuted(clusterConnection.set("A", "value"), 1);// 6373
        assertExecuted(clusterConnection.set("t", "value"), 1);// 16023
        assertExecuted(clusterConnection.set("p", "value"), 1);// 15891

        clusterClient.setOptions(new ClusterClientOptions.Builder().refreshClusterView(false).build());

        shiftAllSlotsToNode1();

        assertExecuted(clusterConnection.set("A", "value"), 1);// 6373
        assertExecuted(clusterConnection.set("t", "value"), 2);// 16023
        assertExecuted(clusterConnection.set("p", "value"), 2);// 15891

        clusterClient.setOptions(new ClusterClientOptions.Builder().refreshClusterView(true).build());

        WaitFor.waitOrTimeout(new Condition() {
            @Override
            public boolean isSatisfied() {
                if (clusterClient.getPartitions().size() == 2) {
                    for (RedisClusterNode redisClusterNode : clusterClient.getPartitions()) {
                        if (redisClusterNode.getSlots().size() > 16380) {
                            return true;
                        }
                    }
                }

                return false;
            }
        }, timeout(seconds(6)));

        assertExecuted(clusterConnection.set("A", "value"), 1);// 6373
        assertExecuted(clusterConnection.set("t", "value"), 1);// 16023
        assertExecuted(clusterConnection.set("p", "value"), 1);// 15891

    }

    protected void shiftAllSlotsToNode1() throws InterruptedException, TimeoutException {
        for (int i = 12000; i < 16384; i += 2) {
            redis2.clusterDelSlots(i, i + 1);
            redis1.clusterDelSlots(i, i + 1);
        }
        waitForSlots(redis2, 0);

        for (int i = 12000; i < 16384; i += 2) {
            redis1.clusterAddSlots(i, i + 1);
        }
        waitForSlots(redis1, 16384);

        WaitFor.waitOrTimeout(new Condition() {
            @Override
            public boolean isSatisfied() {
                return clusterRule.isStable();
            }
        }, timeout(seconds(6)));
    }

    @Test
    public void expireStaleConnections() throws Exception {

        clusterClient.setOptions(new ClusterClientOptions.Builder().refreshClusterView(true).refreshPeriod(1, TimeUnit.SECONDS)
                .build());
        RedisAdvancedClusterAsyncConnection<String, String> clusterConnection = clusterClient.connectClusterAsync();

        setup2Masters();

        PooledClusterConnectionProvider clusterConnectionProvider = getPooledClusterConnectionProvider(clusterConnection);

        assertThat(clusterConnectionProvider.getConnectionCount()).isEqualTo(0);

        assertExecuted(clusterConnection.set("A", "value"), 1);// 6373
        assertExecuted(clusterConnection.set("t", "value"), 1);// 16023
        assertExecuted(clusterConnection.set("p", "value"), 1);// 15891

        assertThat(clusterConnectionProvider.getConnectionCount()).isEqualTo(2);

        Partitions partitions = ClusterPartitionParser.parse(redis1.clusterNodes());
        for (RedisClusterNode redisClusterNode : partitions.getPartitions()) {
            if (!redisClusterNode.getFlags().contains(RedisClusterNode.NodeFlag.MYSELF)) {
                redis1.clusterForget(redisClusterNode.getNodeId());
            }
        }

        partitions = ClusterPartitionParser.parse(redis2.clusterNodes());
        for (RedisClusterNode redisClusterNode : partitions.getPartitions()) {
            if (!redisClusterNode.getFlags().contains(RedisClusterNode.NodeFlag.MYSELF)) {
                redis2.clusterForget(redisClusterNode.getNodeId());
            }
        }

        WaitFor.waitOrTimeout(new Condition() {
            @Override
            public boolean isSatisfied() {
                return clusterClient.getPartitions().size() == 1;
            }
        }, timeout(seconds(6)));

        assertThat(clusterConnectionProvider.getConnectionCount()).isEqualTo(1);

    }

    protected PooledClusterConnectionProvider getPooledClusterConnectionProvider(
            RedisAdvancedClusterAsyncConnection clusterAsyncConnection) {

        RedisAdvancedClusterAsyncConnectionImpl clusterConnection = (RedisAdvancedClusterAsyncConnectionImpl) clusterAsyncConnection;
        ClusterDistributionChannelWriter writer = clusterConnection.getWriter();
        return (PooledClusterConnectionProvider) writer.getClusterConnectionProvider();
    }

    private void assertExecuted(RedisFuture<String> set, int execCount) throws Exception {
        set.get();
        assertThat(set.getError()).isNull();
        assertThat(set.get()).isEqualTo("OK");

        ClusterCommand<?, ?, ?> command = (ClusterCommand<?, ?, ?>) set;
        assertThat(command.getExecutions()).isEqualTo(execCount);
    }

    protected void verifyDeleteSlots(final RedisClusterConnection<String, String> connection, final Set<Integer> slots) {
        try {
            WaitFor.waitOrTimeout(new Condition() {
                @Override
                public boolean isSatisfied() {
                    RedisClusterNode ownPartition = getOwnPartition(connection);
                    boolean condition = ownPartition.getSlots().isEmpty();
                    if (!ownPartition.getSlots().isEmpty()) {
                        deleteSlots(connection, slots);
                    }
                    return condition;
                }
            }, timeout(seconds(5)));
        } catch (Exception e) {

            RedisClusterNode ownPartition = getOwnPartition(connection);
            fail("Slots not deleted, Slots on " + ownPartition.getUri() + ":" + ownPartition.getSlots(), e);
        }
    }

    private void deleteSlots(RedisClusterConnection<String, String> connection, Set<Integer> slots) {
        for (Integer slot : slots) {
            connection.clusterDelSlots(slot);
        }
    }

    @Test
    public void clusterSetSlots() throws Exception {

        redis1.clusterMeet(host, port2);
        waitForCluster(redis1);
        waitForCluster(redis2);

        add6SlotsEach();

        waitForSlots(redis1, 6);
        waitForSlots(redis2, 6);

        redis1.clusterSetSlotNode(6, getNodeId(redis2));

        waitForSlots(redis1, 5);

        Partitions partitions = ClusterPartitionParser.parse(redis1.clusterNodes());
        for (RedisClusterNode redisClusterNode : partitions.getPartitions()) {
            if (redisClusterNode.getFlags().contains(RedisClusterNode.NodeFlag.MYSELF)) {
                assertThat(redisClusterNode.getSlots()).isEqualTo(ImmutableList.of(1, 2, 3, 4, 5));
            }
        }
    }

    private void add6SlotsEach() {
        for (int i = 1; i < 7; i++) {
            redis1.clusterAddSlots(i);
        }
        for (int i = 7; i < 13; i++) {
            redis2.clusterAddSlots(i);
        }
    }

    private void waitForSlots(final RedisClusterConnection<String, String> nodeConnection, final int expectedCount)
            throws InterruptedException, TimeoutException {
        try {
            WaitFor.waitOrTimeout(new Condition() {
                @Override
                public boolean isSatisfied() {
                    RedisClusterNode ownPartition = getOwnPartition(nodeConnection);
                    return ownPartition.getSlots().size() == expectedCount;
                }
            }, timeout(seconds(10)));
        } catch (Exception e) {
            RedisClusterNode ownPartition = getOwnPartition(nodeConnection);
            fail("Fail on waiting for slots on " + ownPartition.getUri() + ", expected count " + expectedCount + ", actual: "
                    + ownPartition.getSlots().size() + " (" + ownPartition.getSlots() + ")", e);
        }
    }

    @Test
    public void clusterSlotMigrationImport() throws Exception {

        redis1.clusterMeet(host, port2);
        waitForCluster(redis1);
        waitForCluster(redis2);

        add6SlotsEach();

        waitForSlots(redis1, 6);
        waitForSlots(redis2, 6);

        String nodeId1 = getNodeId(redis1);
        String nodeId2 = getNodeId(redis2);
        assertThat(redis1.clusterSetSlotMigrating(6, nodeId2)).isEqualTo("OK");
        assertThat(redis2.clusterSetSlotImporting(6, nodeId2)).isEqualTo("OK");
    }

    @Test
    public void clusterSlotMigrationImportWhileOperations() throws Exception {

        RedisAdvancedClusterAsyncConnection<String, String> clusterConnection = clusterClient.connectClusterAsync();
        setup2Masters();

        String nodeId1 = getNodeId(redis1);
        String nodeId2 = getNodeId(redis2);
        assertThat(redis1.clusterSetSlotMigrating(6373, nodeId2)).isEqualTo("OK");
        assertThat(redis2.clusterSetSlotImporting(6373, nodeId2)).isEqualTo("OK");

        assertExecuted(clusterConnection.set("A", "value"), 2);// 6373
    }

}

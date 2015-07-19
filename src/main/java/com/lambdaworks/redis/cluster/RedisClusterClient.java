package com.lambdaworks.redis.cluster;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.io.Closeable;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.Lists;
import com.lambdaworks.redis.AbstractRedisClient;
import com.lambdaworks.redis.RedisAsyncConnectionImpl;
import com.lambdaworks.redis.RedisChannelWriter;
import com.lambdaworks.redis.RedisClusterConnection;
import com.lambdaworks.redis.RedisException;
import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.cluster.models.partitions.Partitions;
import com.lambdaworks.redis.cluster.models.partitions.RedisClusterNode;
import com.lambdaworks.redis.codec.RedisCodec;
import com.lambdaworks.redis.codec.Utf8StringCodec;
import com.lambdaworks.redis.protocol.CommandHandler;
import com.lambdaworks.redis.protocol.RedisCommand;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * A scalable thread-safe <a href="http://redis.io/">Redis</a> cluster client. Multiple threads may share one connection
 * provided they avoid blocking and transactional operations such as BLPOP and MULTI/EXEC.
 *
 * @author <a href="mailto:mpaluch@paluch.biz">Mark Paluch</a>
 * @since 3.0
 */
public class RedisClusterClient extends AbstractRedisClient {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(RedisClusterClient.class);

    protected Partitions partitions;
    protected List<RedisURI> initialUris = Lists.newArrayList();
    protected ClusterTopologyRefresh refresh = new ClusterTopologyRefresh(this);

    protected AtomicBoolean clusterTopologyRefreshActivated = new AtomicBoolean(false);

    private RedisClusterClient() {
        setOptions(new ClusterClientOptions.Builder().build());
    }

    /**
     * Initialize the client with an initial cluster URI.
     *
     * @param initialUri initial cluster URI
     */
    public RedisClusterClient(RedisURI initialUri) {
        this(Collections.singletonList(checkNotNull(initialUri, "RedisURI (initial uri) must not be null")));
    }

    /**
     * Initialize the client with a list of cluster URI's. All uris are tried in sequence for connecting initially to the
     * cluster. If any uri is successful for connection, the others are not tried anymore. The initial uri is needed to discover
     * the cluster structure for distributing the requests.
     *
     * @param initialUris list of initial cluster URIs
     */
    public RedisClusterClient(List<RedisURI> initialUris) {
        this.initialUris = initialUris;
        checkNotNull(initialUris, "initialUris must not be null");
        checkArgument(!initialUris.isEmpty(), "initialUris must not be empty");
        setDefaultTimeout(getFirstUri().getTimeout(), getFirstUri().getUnit());
        setOptions(new ClusterClientOptions.Builder().build());
    }

    /**
     * Open a new synchronous connection to the redis cluster that treats keys and values as UTF-8 strings.
     *
     * @return A new connection.
     */
    public RedisAdvancedClusterConnection<String, String> connectCluster() {

        return connectCluster(newStringStringCodec());
    }

    /**
     * Open a new synchronous connection to the redis server. Use the supplied {@link RedisCodec codec} to encode/decode keys
     * and values.
     *
     * @param codec Use this codec to encode/decode keys and values.
     * @param <K> Key type.
     * @param <V> Value type.
     * @return A new connection.
     */
    @SuppressWarnings("unchecked")
    public <K, V> RedisAdvancedClusterConnection<K, V> connectCluster(RedisCodec<K, V> codec) {

        return (RedisAdvancedClusterConnection<K, V>) syncHandler(connectClusterAsyncImpl(codec),
                RedisAdvancedClusterConnection.class, RedisClusterConnection.class);
    }

    /**
     * Creates a connection to the redis cluster.
     *
     * @return A new connection.
     */
    public RedisAdvancedClusterAsyncConnection<String, String> connectClusterAsync() {
        return connectClusterAsyncImpl(newStringStringCodec(), getSocketAddressSupplier());
    }

    /**
     * Creates a connection to the redis cluster.
     *
     * @param codec Use this codec to encode/decode keys and values.
     * @param <K> Key type.
     * @param <V> Value type.
     * @return A new connection.
     */
    public <K, V> RedisAdvancedClusterAsyncConnection<K, V> connectClusterAsync(RedisCodec<K, V> codec) {
        return connectClusterAsyncImpl(codec, getSocketAddressSupplier());
    }

    protected RedisAsyncConnectionImpl<String, String> connectAsyncImpl(SocketAddress socketAddress) {
        return connectAsyncImpl(newStringStringCodec(), socketAddress);
    }

    /**
     * Create a connection to a redis socket address.
     *
     * @param socketAddress initial connect
     * @param <K> Key type.
     * @param <V> Value type.
     * @return a new connection
     */
    <K, V> RedisAsyncConnectionImpl<K, V> connectAsyncImpl(RedisCodec<K, V> codec, final SocketAddress socketAddress) {

        logger.debug("connectAsyncImpl(" + socketAddress + ")");
        Queue<RedisCommand<K, V, ?>> queue = new ArrayDeque<RedisCommand<K, V, ?>>();

        CommandHandler<K, V> handler = new CommandHandler<K, V>(clientOptions, queue);
        RedisAsyncConnectionImpl<K, V> connection = newRedisAsyncConnectionImpl(handler, codec, timeout, unit);

        connectAsyncImpl(handler, connection, new Supplier<SocketAddress>() {
            @Override
            public SocketAddress get() {
                return socketAddress;
            }
        });

        connection.registerCloseables(closeableResources, connection);

        return connection;
    }

    <K, V> RedisAsyncConnectionImpl<K, V> connectClusterAsyncImpl(RedisCodec<K, V> codec) {
        return connectClusterAsyncImpl(codec, getSocketAddressSupplier());
    }

    /**
     * Create a clustered connection with command distributor.
     *
     * @param codec the codec to use
     * @param socketAddressSupplier address supplier for initial connect and re-connect
     * @param <K> Key type.
     * @param <V> Value type.
     * @return a new connection
     */
    <K, V> RedisAdvancedClusterAsyncConnectionImpl<K, V> connectClusterAsyncImpl(RedisCodec<K, V> codec,
            final Supplier<SocketAddress> socketAddressSupplier) {

        if (partitions == null) {
            initializePartitions();
        }

        logger.debug("connectCluster(" + socketAddressSupplier.get() + ")");
        Queue<RedisCommand<K, V, ?>> queue = new ArrayDeque<RedisCommand<K, V, ?>>();

        CommandHandler<K, V> handler = new CommandHandler<K, V>(clientOptions, queue);

        final PooledClusterConnectionProvider<K, V> pooledClusterConnectionProvider = new PooledClusterConnectionProvider<K, V>(
                this, codec);

        final ClusterDistributionChannelWriter<K, V> clusterWriter = new ClusterDistributionChannelWriter<K, V>(handler,
                pooledClusterConnectionProvider);
        RedisAdvancedClusterAsyncConnectionImpl<K, V> connection = newRedisAdvancedClusterAsyncConnectionImpl(clusterWriter,
                codec, timeout, unit);

        connection.setPartitions(partitions);
        connectAsyncImpl(handler, connection, socketAddressSupplier);

        connection.registerCloseables(closeableResources, connection, clusterWriter, pooledClusterConnectionProvider);

        if (getFirstUri().getPassword() != null) {
            connection.auth(new String(getFirstUri().getPassword()));
        }

        return connection;

    }

    /**
     * Reload partitions and re-initialize the distribution table.
     */
    public void reloadPartitions() {
        if (partitions == null) {
            initializePartitions();
            partitions.updateCache();
        } else {
            Partitions loadedPartitions = loadPartitions();
            this.partitions.getPartitions().clear();
            this.partitions.getPartitions().addAll(loadedPartitions.getPartitions());
            this.partitions.reload(loadedPartitions.getPartitions());
        }

        updatePartitionsInConnections();
    }

    protected void updatePartitionsInConnections() {

        forEachClusterConnection(new Predicate<RedisAdvancedClusterAsyncConnectionImpl<?, ?>>() {
            @Override
            public boolean apply(RedisAdvancedClusterAsyncConnectionImpl<?, ?> input) {
                input.setPartitions(partitions);
                return true;
            }
        });
    }

    protected void initializePartitions() {

        Partitions loadedPartitions = loadPartitions();
        this.partitions = loadedPartitions;
    }

    /**
     * Retrieve the cluster view. Partitions are shared amongst all connections opened by this client instance.
     *
     * @return the partitions.
     */
    public Partitions getPartitions() {
        if (partitions == null) {
            initializePartitions();
        }
        return partitions;
    }

    /**
     * Retrieve partitions.
     * 
     * @return Partitions
     */
    protected Partitions loadPartitions() {

        Map<RedisURI, Partitions> partitions = refresh.loadViews(initialUris);

        if (partitions.isEmpty()) {
            throw new RedisException("Cannot retrieve initial cluster partitions from initial URIs " + initialUris);
        }

        Partitions loadedPartitions = partitions.values().iterator().next();
        RedisURI viewedBy = refresh.getViewedBy(partitions, loadedPartitions);

        for (RedisClusterNode partition : loadedPartitions) {
            if (viewedBy != null && viewedBy.getPassword() != null) {
                partition.getUri().setPassword(new String(viewedBy.getPassword()));
            }
        }

        if (getOptions() instanceof ClusterClientOptions) {
            ClusterClientOptions options = (ClusterClientOptions) getOptions();
            if (options.isRefreshClusterView()) {
                synchronized (clusterTopologyRefreshActivated) {
                    if (!clusterTopologyRefreshActivated.get()) {
                        final Runnable r = new ClusterTopologyRefreshTask();
                        genericWorkerPool.scheduleAtFixedRate(r, options.getRefreshPeriod(), options.getRefreshPeriod(),
                                options.getRefreshPeriodUnit());
                        clusterTopologyRefreshActivated.set(true);
                    }
                }
            }
        }

        return loadedPartitions;
    }

    protected boolean isEventLoopActive() {
        if (genericWorkerPool.isShuttingDown() || genericWorkerPool.isShutdown() || genericWorkerPool.isTerminated()) {
            return false;
        }

        return true;
    }

    /**
     * Construct a new {@link RedisAsyncConnectionImpl}. Can be overridden in order to construct a subclass of
     * {@link RedisAsyncConnectionImpl}. These connections are the "inner" connections used by the
     * {@link ClusterDistributionChannelWriter}.
     *
     * @param channelWriter the channel writer
     * @param codec the codec to use
     * @param timeout Timeout value
     * @param unit Timeout unit
     * @param <K> Key type.
     * @param <V> Value type.
     * @return RedisAsyncConnectionImpl&lt;K, V&gt; instance
     */
    protected <K, V> RedisAsyncConnectionImpl<K, V> newRedisAsyncConnectionImpl(RedisChannelWriter<K, V> channelWriter,
            RedisCodec<K, V> codec, long timeout, TimeUnit unit) {
        return new RedisAsyncConnectionImpl<K, V>(channelWriter, codec, timeout, unit);
    }

    /**
     * Construct a new {@link RedisAdvancedClusterAsyncConnectionImpl}. Can be overridden in order to construct a subclass of
     * {@link RedisAdvancedClusterAsyncConnectionImpl}
     *
     * @param channelWriter the channel writer
     * @param codec the codec to use
     * @param timeout Timeout value
     * @param unit Timeout unit
     * @param <K> Key type.
     * @param <V> Value type.
     * @return RedisAdvancedClusterAsyncConnectionImpl&lt;K, V&gt; instance
     */
    protected <K, V> RedisAdvancedClusterAsyncConnectionImpl<K, V> newRedisAdvancedClusterAsyncConnectionImpl(
            RedisChannelWriter<K, V> channelWriter, RedisCodec<K, V> codec, long timeout, TimeUnit unit) {
        return new RedisAdvancedClusterAsyncConnectionImpl<K, V>(channelWriter, codec, timeout, unit);
    }

    protected RedisURI getFirstUri() {
        checkState(!initialUris.isEmpty(), "initialUris must not be empty");
        return initialUris.get(0);
    }

    private Supplier<SocketAddress> getSocketAddressSupplier() {
        return new Supplier<SocketAddress>() {
            @Override
            public SocketAddress get() {
                if (partitions != null) {
                    for (RedisClusterNode partition : partitions) {
                        if (partition.getUri() != null && partition.getUri().getResolvedAddress() != null) {
                            return partition.getUri().getResolvedAddress();
                        }
                    }
                }

                return getFirstUri().getResolvedAddress();
            }
        };
    }

    protected Utf8StringCodec newStringStringCodec() {
        return new Utf8StringCodec();
    }

    public void setPartitions(Partitions partitions) {
        this.partitions = partitions;
    }

    protected void forEachClusterConnection(Predicate<RedisAdvancedClusterAsyncConnectionImpl<?, ?>> function) {

        forEachCloseable(new Predicate<Closeable>() {
            @Override
            public boolean apply(Closeable input) {
                return input instanceof RedisAdvancedClusterAsyncConnectionImpl;
            }
        }, function);
    }

    protected <T extends Closeable> void forEachCloseable(Predicate<? super Closeable> selector, Predicate<T> function) {
        for (Closeable c : closeableResources) {
            if (selector.apply(c)) {
                function.apply((T) c);
            }
        }
    }

    /**
     * Set the {@link ClusterClientOptions} for the client.
     * 
     * @param clientOptions
     */
    public void setOptions(ClusterClientOptions clientOptions) {
        super.setOptions(clientOptions);
    }

    private class ClusterTopologyRefreshTask implements Runnable {

        public ClusterTopologyRefreshTask() {
        }

        @Override
        public void run() {
            if (isEventLoopActive() && getOptions() instanceof ClusterClientOptions) {
                ClusterClientOptions options = (ClusterClientOptions) getOptions();
                if (!options.isRefreshClusterView()) {
                    return;
                }
            } else {
                return;
            }

            List<RedisURI> seed;
            if (partitions == null || partitions.size() == 0) {
                seed = RedisClusterClient.this.initialUris;
            } else {
                seed = Lists.newArrayList();
                for (RedisClusterNode partition : partitions) {
                    seed.add(partition.getUri());
                }
            }

            Map<RedisURI, Partitions> partitions = refresh.loadViews(seed);
            List<Partitions> values = Lists.newArrayList(partitions.values());
            if (!values.isEmpty() && refresh.isChanged(getPartitions(), values.get(0))) {
                logger.debug("Using a new cluster topology");
                getPartitions().reload(values.get(0).getPartitions());
                updatePartitionsInConnections();

                if (isEventLoopActive()) {
                    genericWorkerPool.submit(new CloseStaleConnectionsTask());
                }

            }
        }
    }

    private class CloseStaleConnectionsTask implements Runnable {
        @Override
        public void run() {
            forEachClusterConnection(new Predicate<RedisAdvancedClusterAsyncConnectionImpl<?, ?>>() {
                @Override
                public boolean apply(RedisAdvancedClusterAsyncConnectionImpl<?, ?> input) {

                    ClusterDistributionChannelWriter<?, ?> writer = (ClusterDistributionChannelWriter<?, ?>) input
                            .getChannelWriter();
                    writer.getClusterConnectionProvider().closeStaleConnections();
                    return true;
                }
            });

        }
    }
}

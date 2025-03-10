/*
 * Copyright 2023 CeresDB Project Authors. Licensed under Apache-2.0.
 */
package io.ceresdb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.ceresdb.models.RequestContext;
import io.ceresdb.proto.internal.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.ceresdb.common.Display;
import io.ceresdb.common.Endpoint;
import io.ceresdb.common.Lifecycle;
import io.ceresdb.common.util.Clock;
import io.ceresdb.common.util.Cpus;
import io.ceresdb.common.util.MetricsUtil;
import io.ceresdb.common.util.Requires;
import io.ceresdb.common.util.SharedScheduledPool;
import io.ceresdb.common.util.TopKSelector;
import io.ceresdb.errors.RouteTableException;
import io.ceresdb.options.RouterOptions;
import io.ceresdb.rpc.Context;
import io.ceresdb.rpc.Observer;
import io.ceresdb.rpc.RpcClient;
import io.ceresdb.rpc.errors.RemotingException;
import io.ceresdb.util.Utils;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Timer;

/**
 * A route rpc client which implement RouteMode.Direct
 *
 * cached the routing table information locally
 * and will refresh when the server returns an error code of INVALID_ROUTE
 *
 */
public class RouterClient implements Lifecycle<RouterOptions>, Display, Iterable<Route> {

    private static final Logger LOG = LoggerFactory.getLogger(RouterClient.class);

    // I don't think they needs to be open to user configuration, so I'll just put a fixed value here
    private static final float CLEAN_CACHE_THRESHOLD   = 0.75f;
    private static final float CLEAN_THRESHOLD         = 0.1f;
    private static final int   MAX_CONTINUOUS_GC_TIMES = 3;

    private static final SharedScheduledPool CLEANER_POOL   = Utils.getSharedScheduledPool("route_cache_cleaner", 1);
    private static final SharedScheduledPool REFRESHER_POOL = Utils.getSharedScheduledPool("route_cache_refresher",
            Math.min(4, Cpus.cpus()));

    private ScheduledExecutorService cleaner;
    private ScheduledExecutorService refresher;
    protected RouterOptions          opts;
    protected RpcClient              rpcClient;
    protected RouterByTables         router;
    protected InnerMetrics           metrics;

    private final ConcurrentMap<String, Route> routeCache = new ConcurrentHashMap<>();

    static final class InnerMetrics {
        final Histogram refreshedSize;
        final Histogram cachedSize;
        final Histogram gcTimes;
        final Histogram gcItems;
        final Timer     gcTimer;

        private InnerMetrics(final Endpoint name) {
            final String nameSuffix = name.toString();
            this.refreshedSize = MetricsUtil.histogram("route_for_tables_refreshed_size", nameSuffix);
            this.cachedSize = MetricsUtil.histogram("route_for_tables_cached_size", nameSuffix);
            this.gcTimes = MetricsUtil.histogram("route_for_tables_gc_times", nameSuffix);
            this.gcItems = MetricsUtil.histogram("route_for_tables_gc_items", nameSuffix);
            this.gcTimer = MetricsUtil.timer("route_for_tables_gc_timer", nameSuffix);
        }

        Histogram refreshedSize() {
            return this.refreshedSize;
        }

        Histogram cachedSize() {
            return this.cachedSize;
        }

        Histogram gcTimes() {
            return this.gcTimes;
        }

        Histogram gcItems() {
            return this.gcItems;
        }

        Timer gcTimer() {
            return this.gcTimer;
        }
    }

    @Override
    public boolean init(final RouterOptions opts) {
        this.opts = Requires.requireNonNull(opts, "RouterClient.opts").copy();
        this.rpcClient = this.opts.getRpcClient();

        final Endpoint address = Requires.requireNonNull(this.opts.getClusterAddress(), "Null.clusterAddress");

        this.router = new RouterByTables(address);
        this.metrics = new InnerMetrics(address);

        final long gcPeriod = this.opts.getGcPeriodSeconds();
        if (gcPeriod > 0) {
            this.cleaner = CLEANER_POOL.getObject();
            this.cleaner.scheduleWithFixedDelay(this::gc, Utils.randomInitialDelay(300), gcPeriod, TimeUnit.SECONDS);

            LOG.info("Route table cache cleaner has been started.");
        }

        return true;
    }

    @Override
    public void shutdownGracefully() {
        if (this.rpcClient != null) {
            this.rpcClient.shutdownGracefully();
        }
        if (this.cleaner != null) {
            CLEANER_POOL.returnObject(this.cleaner);
            this.cleaner = null;
        }
        if (this.refresher != null) {
            REFRESHER_POOL.returnObject(this.refresher);
            this.refresher = null;
        }
        clearRouteCache();
    }

    @Override
    public Iterator<Route> iterator() {
        return this.routeCache.values().iterator();
    }

    public Route clusterRoute() {
        return Route.of(this.opts.getClusterAddress());
    }

    public CompletableFuture<Map<String, Route>> routeFor(final RequestContext reqCtx,
                                                          final Collection<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return Utils.completedCf(Collections.emptyMap());
        }

        final Map<String, Route> local = new HashMap<>();
        final List<String> misses = new ArrayList<>();

        tables.forEach(table -> {
            final Route r = this.routeCache.get(table);
            if (r == null) {
                misses.add(table);
            } else {
                local.put(table, r);
            }
        });

        if (misses.isEmpty()) {
            return Utils.completedCf(local);
        }

        return routeRefreshFor(reqCtx, misses) // refresh from remote
                .thenApply(remote -> { // then merge result
                    final Map<String, Route> ret;
                    if (remote.size() > local.size()) {
                        remote.putAll(local);
                        ret = remote;
                    } else {
                        local.putAll(remote);
                        for (String miss : misses) {
                            local.putIfAbsent(miss, Route.of(miss, opts.getClusterAddress()));
                        }
                        ret = local;
                    }
                    return ret;
                }) //
                .thenApply(hits -> { // update cache hits
                    final long now = Clock.defaultClock().getTick();
                    hits.values().forEach(route -> route.tryWeekSetHit(now));
                    return hits;
                });
    }

    public CompletableFuture<Map<String, Route>> routeRefreshFor(final RequestContext reqCtx,
                                                                 final Collection<String> tables) {
        return this.router.routeFor(reqCtx, tables).whenComplete((remote, err) -> {
            if (err == null) {
                this.routeCache.putAll(remote);
                this.metrics.refreshedSize().update(remote.size());
                this.metrics.cachedSize().update(this.routeCache.size());

                LOG.info("Route refreshed: {}, cached_size={}.", tables, this.routeCache.size());
            } else {
                LOG.warn("Route refresh failed: {}.", tables, err);
            }
        });
    }

    public void clearRouteCacheBy(final Collection<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return;
        }
        tables.forEach(this.routeCache::remove);
    }

    public int clearRouteCache() {
        final int size = this.routeCache.size();
        this.routeCache.clear();
        return size;
    }

    public void gc() {
        this.metrics.gcTimer().time(() -> this.metrics.gcTimes().update(gc0(0)));
    }

    private int gc0(final int times) {
        if (this.routeCache.size() < this.opts.getMaxCachedSize() * CLEAN_CACHE_THRESHOLD) {
            LOG.info("Now that the number of cached entries is {}.", this.routeCache.size());
            return times;
        }

        LOG.warn("Now that the number of cached entries [{}] is about to exceed its limit [{}], we need to clean up.",
                //
                this.routeCache.size(), this.opts.getMaxCachedSize());

        final int itemsToGC = (int) (this.routeCache.size() * CLEAN_THRESHOLD);
        if (itemsToGC <= 0) {
            LOG.warn("No more need to be clean.");
            return times;
        }

        final List<String> topK = TopKSelector.selectTopK( //
                this.routeCache.entrySet(), //
                itemsToGC, //
                (o1, o2) -> -Long.compare(o1.getValue().getLastHit(), o2.getValue().getLastHit()) //
        ) //
                .map(Map.Entry::getKey) //
                .collect(Collectors.toList());

        this.metrics.gcItems().update(topK.size());

        clearRouteCacheBy(topK);

        LOG.warn("Cleaned {} entries from route cache, now entries size {}.", itemsToGC, this.routeCache.size());

        if (this.routeCache.size() > this.opts.getMaxCachedSize() * CLEAN_CACHE_THRESHOLD
            && times < MAX_CONTINUOUS_GC_TIMES) {
            LOG.warn("Now we need to work continuously, this will be the {}th attempt.", times + 1);
            return gc0(times + 1);
        }

        return times;
    }

    public <Req, Resp> CompletableFuture<Resp> invoke(final Endpoint endpoint, //
                                                      final Req request, //
                                                      final Context ctx) {
        return invoke(endpoint, request, ctx, -1 /* use default rpc timeout */);
    }

    public <Req, Resp> CompletableFuture<Resp> invoke(final Endpoint endpoint, //
                                                      final Req request, //
                                                      final Context ctx, //
                                                      final long timeoutMs) {
        final CompletableFuture<Resp> future = new CompletableFuture<>();

        try {
            this.rpcClient.invokeAsync(endpoint, request, ctx, new Observer<Resp>() {

                @Override
                public void onNext(final Resp value) {
                    future.complete(value);
                }

                @Override
                public void onError(final Throwable err) {
                    future.completeExceptionally(err);
                }
            }, timeoutMs);

        } catch (final RemotingException e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    public <Req, Resp> void invokeServerStreaming(final Endpoint endpoint, //
                                                  final Req request, //
                                                  final Context ctx, //
                                                  final Observer<Resp> observer) {
        try {
            this.rpcClient.invokeServerStreaming(endpoint, request, ctx, observer);
        } catch (final RemotingException e) {
            observer.onError(e);
        }
    }

    public <Req, Resp> Observer<Req> invokeClientStreaming(final Endpoint endpoint, //
                                                           final Req defaultReqIns, //
                                                           final Context ctx, //
                                                           final Observer<Resp> respObserver) {
        try {
            return this.rpcClient.invokeClientStreaming(endpoint, defaultReqIns, ctx, respObserver);
        } catch (final RemotingException e) {
            respObserver.onError(e);
            return new Observer.RejectedObserver<>(e);
        }
    }

    private Collection<Endpoint> reserveAddresses() {
        return this.routeCache.values().stream().map(Route::getEndpoint).collect(Collectors.toSet());
    }

    private boolean checkConn(final Endpoint endpoint, final boolean create) {
        return this.rpcClient.checkConnection(endpoint, create);
    }

    @Override
    public void display(final Printer out) {
        out.println("--- RouterClient ---") //
                .print("opts=") //
                .println(this.opts) //
                .print("routeCache.size=") //
                .println(this.routeCache.size());

        if (this.rpcClient != null) {
            out.println("");
            this.rpcClient.display(out);
        }
    }

    @Override
    public String toString() {
        return "RouterClient{" + //
               "opts=" + opts + //
               ", rpcClient=" + rpcClient + //
               ", router=" + router + //
               '}';
    }

    private class RouterByTables implements Router<Collection<String>, Map<String, Route>> {

        private final Endpoint endpoint;

        private RouterByTables(Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public CompletableFuture<Map<String, Route>> routeFor(final RequestContext reqCtx,
                                                              final Collection<String> tables) {
            if (tables == null || tables.isEmpty()) {
                return Utils.completedCf(Collections.emptyMap());
            }

            final Storage.RouteRequest req = Storage.RouteRequest.newBuilder()
                    .setContext(Storage.RequestContext.newBuilder().setDatabase(reqCtx.getDatabase()).build())
                    .addAllTables(tables).build();
            final Context ctx = Context.of("call_priority", "100"); // Mysterious trick!!! ＼（＾▽＾）／
            final CompletableFuture<Storage.RouteResponse> f = invokeRpc(req, ctx);

            return f.thenCompose(resp -> {
                if (Utils.isSuccess(resp.getHeader())) {
                    final Map<String, Route> ret = resp.getRoutesList().stream()
                            .collect(Collectors.toMap(Storage.Route::getTable, this::toRouteObj));
                    return Utils.completedCf(ret);
                }

                return Utils.errorCf(new RouteTableException("Fail to get route table: " + resp.getHeader()));
            });
        }

        private CompletableFuture<Storage.RouteResponse> invokeRpc(final Storage.RouteRequest req, final Context ctx) {
            if (checkConn(this.endpoint, true)) {
                return invoke(this.endpoint, req, ctx);
            }

            LOG.warn("Fail to connect to the cluster address: {}.", this.endpoint);

            final Collection<Endpoint> reserves = reserveAddresses();
            // RR
            int i = 0;
            for (final Endpoint ep : reserves) {
                LOG.warn("Try to invoke to the {}th server {}.", ++i, ep);
                if (checkConn(ep, false)) {
                    return invoke(ep, req, ctx);
                }
            }

            return Utils.errorCf(new RouteTableException("Fail to connect to: " + this.endpoint));
        }

        private Route toRouteObj(final Storage.Route r) {
            final Storage.Endpoint ep = Requires.requireNonNull(r.getEndpoint(), "CeresDB.Endpoint");
            return Route.of(r.getTable(), Endpoint.of(ep.getIp(), ep.getPort()));
        }
    }
}

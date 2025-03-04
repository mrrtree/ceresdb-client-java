/*
 * Copyright 2023 CeresDB Project Authors. Licensed under Apache-2.0.
 */
package io.ceresdb;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import io.ceresdb.common.util.MetricsUtil;
import io.ceresdb.common.util.internal.ReferenceFieldUpdater;
import io.ceresdb.common.util.internal.Updaters;
import io.ceresdb.models.Err;
import io.ceresdb.models.Point;
import io.ceresdb.models.Result;
import io.ceresdb.models.WriteOk;
import io.ceresdb.models.WriteRequest;
import io.ceresdb.options.CeresDBOptions;
import io.ceresdb.util.TestUtil;
import io.ceresdb.util.Utils;

@RunWith(value = MockitoJUnitRunner.class)
public class CeresDBClientTest {

    private static final ReferenceFieldUpdater<CeresDBClient, WriteClient> WC_UPDATER = Updaters //
            .newReferenceFieldUpdater(CeresDBClient.class, "writeClient");

    private CeresDBClient  client;
    private CeresDBOptions opts;
    @Mock
    private WriteClient    writeClient;

    @Before
    public void before() {
        this.opts = CeresDBOptions.newBuilder("127.0.0.1", 8081, RouteMode.DIRECT) //
                .database("public") //
                .writeMaxRetries(1) //
                .readMaxRetries(1) //
                .build();
        this.client = new CeresDBClient();
    }

    @After
    public void after() {
        MetricsUtil.reportImmediately();
        this.client.shutdownGracefully();
    }

    @Test(expected = IllegalStateException.class)
    public void withoutInitTest() {
        final List<Point> points = TestUtil.newTableTwoPoints("test_table1_not_init");
        this.client.write(new WriteRequest(points));
    }

    @Test(expected = IllegalStateException.class)
    public void repeatedStartTest() {
        this.client.init(this.opts);
        this.client.init(this.opts);
    }

    @Test
    public void instancesTest() {
        this.client.init(this.opts);
        Assert.assertEquals(1, CeresDBClient.instances().size());
        this.client.shutdownGracefully();
        Assert.assertTrue(CeresDBClient.instances().isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void helloWorldTest() throws ExecutionException, InterruptedException {
        initAndMockWriteClient();

        final List<Point> points = TestUtil.newTableTwoPoints("test_table1");

        Mockito.when(this.writeClient.write(new WriteRequest(Mockito.anyList()), Mockito.any())) //
                .thenReturn(Utils.completedCf(WriteOk.ok(2, 0, null).mapToResult()));
        final CompletableFuture<Result<WriteOk, Err>> f = this.client.write(new WriteRequest(points));
        final Result<WriteOk, Err> ret = f.get();
        Assert.assertTrue(ret.isOk());
        final int success = ret.mapOr(-1, WriteOk::getSuccess);
        final int failed = ret.mapOr(-1, WriteOk::getFailed);
        Assert.assertEquals(2, success);
        Assert.assertEquals(0, failed);
    }

    private void initAndMockWriteClient() {
        this.client.init(this.opts);
        WC_UPDATER.set(this.client, this.writeClient);
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.ceresdb;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.ceresdb.common.util.MetricsUtil;
import io.ceresdb.common.util.internal.ThrowUtil;
import io.ceresdb.models.Err;
import io.ceresdb.models.Point;
import io.ceresdb.models.Row;
import io.ceresdb.models.SqlQueryOk;
import io.ceresdb.models.SqlQueryRequest;
import io.ceresdb.models.Result;
import io.ceresdb.models.Value;
import io.ceresdb.models.WriteOk;
import io.ceresdb.models.WriteRequest;
import io.ceresdb.options.CeresDBOptions;
import io.ceresdb.rpc.RpcOptions;
import io.ceresdb.util.StreamWriteBuf;

/**
 *
 * @author xvyang.xy
 */
public class CeresDBTest {

    private static final Logger LOG = LoggerFactory.getLogger(CeresDBTest.class);

    private String TEST_TABLE_NAME = "all_type_test_table_%d";

    private CeresDBOptions opts;
    private CeresDBClient  client;

    @Before
    public void before() throws Exception {
        final RpcOptions rpcOpts = RpcOptions.newDefault();
        rpcOpts.setBlockOnLimit(false);
        rpcOpts.setInitialLimit(32);
        rpcOpts.setLimitKind(RpcOptions.LimitKind.Gradient);
        rpcOpts.setLogOnLimitChange(true);
        this.opts = CeresDBOptions.newBuilder("30.54.154.64", 8831, RouteMode.DIRECT) //
                .tenant("public", "sub_test", "test_token") //
                .rpcOptions(rpcOpts) //
                .writeMaxRetries(0) //
                .readMaxRetries(1) //
                .build();
        this.client = new CeresDBClient();
        final boolean ret = this.client.init(this.opts);

        TEST_TABLE_NAME = String.format(TEST_TABLE_NAME, System.currentTimeMillis());

        final Result<SqlQueryOk, Err> existsResult = this.client
                .sqlQuery(new SqlQueryRequest("EXISTS TABLE %s", TEST_TABLE_NAME)).get();
        if (!existsResult.isOk()) {
            LOG.error("EXISTS TABLE before fail: {}", existsResult.getErr());
            return;
        }
        LOG.info("EXISTS TABLE before: {}.", existsResult.getOk());

        final Result<SqlQueryOk, Err> createResult = this.client.sqlQuery(new SqlQueryRequest("CREATE TABLE %s(" + //
                                                                                              "ts TIMESTAMP NOT NULL," + //
                                                                                              "tString STRING TAG NOT NULL,"
                                                                                              + //
                                                                                              "tInt64 INT64 TAG NULL," + //
                                                                                              "fString STRING NULL," + //
                                                                                              "fBool BOOLEAN NULL," + //
                                                                                              "fFloat64 DOUBLE NULL," + //
                                                                                              "fFloat32 FLOAT NULL," + //
                                                                                              "fInt64 INT64 NULL," + //
                                                                                              "fInt32 INT32 NULL," + //
                                                                                              "fInt16 INT16 NULL," + //
                                                                                              "fInt8 INT8 NULL," + //
                                                                                              "fUint64 UINT64 NULL," + //
                                                                                              "fUint32 UINT32 NULL," + //
                                                                                              "fUint16 UINT16 NULL," + //
                                                                                              "fUint8 UINT8 NULL," + //
                                                                                              "fTimestamp TIMESTAMP NULL,"
                                                                                              + //
                                                                                              "fVarbinary VARBINARY NULL,"
                                                                                              + //
                                                                                              "TIMESTAMP KEY(ts)) ENGINE=Analytic WITH (ttl='7d')",
                TEST_TABLE_NAME)).get();
        if (!createResult.isOk()) {
            LOG.error("CREATE TABLE before fail, {}", createResult.getErr());
            return;
        }

        LOG.info("Start CeresDB client {}, with options: {}, create table {}: {}.", result(ret), this.opts,
                TEST_TABLE_NAME, createResult.getOk());

        final Result<SqlQueryOk, Err> existsResult2 = this.client
                .sqlQuery(new SqlQueryRequest("EXISTS TABLE %s", TEST_TABLE_NAME)).get();
        LOG.info("EXISTS TABLE after: {}.", existsResult2);
    }

    private static String result(final boolean result) {
        return result ? "success" : "failed";
    }

    @After
    public void after() throws Exception {
        final Result<SqlQueryOk, Err> dropResult = this.client
                .sqlQuery(new SqlQueryRequest("DROP TABLE %s", TEST_TABLE_NAME)).get();
        if (!dropResult.isOk()) {
            LOG.error("DROP TABLE after fail, {}", dropResult.getErr());
            return;
        }

        LOG.info("DROP TABLE: {}.", dropResult);
        MetricsUtil.reportImmediately();
        this.client.shutdownGracefully();
    }

    public CeresDBOptions getOpts() {
        return opts;
    }

    @Ignore
    @Test
    public void comprehensiveTest() throws ExecutionException, InterruptedException {
        final Calendar time = Calendar.getInstance();
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ");
        final String timeString = format.format(time.getTime());
        System.out.println("time=" + timeString + " " + time.getTimeInMillis() + " " + time.getTime());

        final int writeCount = ThreadLocalRandom.current().nextInt(10);

        final Result<WriteOk, Err> writeR = write(time, writeCount);

        LOG.info("#comprehensiveTest write result={}.", writeR);

        Assert.assertTrue(writeR.isOk());

        final SqlQueryRequest req = SqlQueryRequest.newBuilder() //
                .sql("select * from %s where ts < to_timestamp_millis('%s')", TEST_TABLE_NAME, timeString) //
                .build();

        final Result<SqlQueryOk, Err> queryR = this.client.sqlQuery(req).get();

        LOG.info("#comprehensiveTest query result={}.", queryR);

        Assert.assertTrue(queryR.isOk());

        final SqlQueryOk ok = queryR.getOk();

        ok.stream().forEach(row -> {
            LOG.info(
                    "Data: ts={}, tString={}, tInt64={}, fString={}, fBool={}, fFloat64={}, fFloat32={}, fInt64={}, fInt32={}, fInt16={},"
                     + //
            "fInt8={}, fUint64={}, fUint32={}, fUint16={}, fUint8={}, fTimestamp={}, fVarbinary={}", //
                    row.getColumnValue("ts").getTimestamp(), //
                    row.getColumnValue("tString").getString(), //
                    row.getColumnValue("tInt64").getInt64(), //
                    row.getColumnValue("fString").getString(), //
                    row.getColumnValue("fBool").getBoolean(), //
                    row.getColumnValue("fFloat64").getFloat64(), //
                    row.getColumnValue("fFloat32").getFloat32(), //
                    row.getColumnValue("fInt64").getInt64(), //
                    row.getColumnValue("fInt32").getInt32(), //
                    row.getColumnValue("fInt16").getInt16(), //
                    row.getColumnValue("fInt8").getInt8(), //
                    row.getColumnValue("fUint64").getUInt64(), //
                    row.getColumnValue("fUint32").getUInt32(), //
                    row.getColumnValue("fUint16").getUInt16(), //
                    row.getColumnValue("fUint8").getUInt8(), //
                    row.getColumnValue("fTimestamp").getTimestamp(), row.getColumnValue("fVarbinary").getVarbinary());
        });

        final Result<SqlQueryOk, Err> alterResult1 = this.client
                .sqlQuery(
                        new SqlQueryRequest("ALTER TABLE %s ADD COLUMN (c18 UINT64, c19 STRING TAG)", TEST_TABLE_NAME))
                .get();
        LOG.info("ALTER TABLE 1: {}.", alterResult1);

        final Result<SqlQueryOk, Err> alterResult2 = this.client
                .sqlQuery(new SqlQueryRequest("ALTER TABLE %s ADD COLUMN c20 STRING TAG", TEST_TABLE_NAME)).get();
        LOG.info("ALTER TABLE 2: {}.", alterResult2);

        final Result<SqlQueryOk, Err> descResult = this.client
                .sqlQuery(new SqlQueryRequest("DESCRIBE %s", TEST_TABLE_NAME)).get();
        LOG.info("DESCRIBE TABLE: {}.", descResult);

        final Result<SqlQueryOk, Err> showResult = this.client
                .sqlQuery(new SqlQueryRequest("SHOW CREATE TABLE %s", TEST_TABLE_NAME)).get();
        LOG.info("SHOW CREATE TABLE: {}.", showResult);

        final Result<SqlQueryOk, Err> queryResult = this.client
                .sqlQuery(new SqlQueryRequest("SELECT * FROM %s", TEST_TABLE_NAME)).get();
        LOG.info("QUERY TABLE: {}.", queryResult);
    }

    private Result<WriteOk, Err> write(final Calendar time, final int writeCount) {
        try {
            return writeAsync(time, writeCount).get();
        } catch (final Throwable t) {
            ThrowUtil.throwException(t);
        }
        return WriteOk.emptyOk().mapToResult();
    }

    private CompletableFuture<Result<WriteOk, Err>> writeAsync(final Calendar time, final int writeCount) {
        return this.client.write(new WriteRequest(makePoints(time, writeCount)));
    }

    private List<Point> makePoints(final Calendar time, final int count) {
        final long timestamp = time.getTimeInMillis();

        Point.PointsBuilder builder = Point.newPointsBuilder(TEST_TABLE_NAME);
        for (long ts = (timestamp - count); ts < timestamp; ts++) {
            builder.addPoint().setTimestamp(ts).addTag("tString", Value.withString("first_c1"))
                    .addTag("tInt64", Value.withInt64(12)).addField("fString", Value.withString("string value"))
                    .addField("fBool", Value.withBoolean(true)).addField("fFloat64", Value.withFloat64(0.64))
                    .addField("fFloat32", Value.withFloat32(0.32f)).addField("fInt64", Value.withInt64(-64))
                    .addField("fInt32", Value.withInt32(-32)).addField("fInt16", Value.withInt16(-16))
                    .addField("fInt8", Value.withInt8(-8)).addField("fUint64", Value.withUInt64(64))
                    .addField("fUint32", Value.withUInt32(32)).addField("fUint16", Value.withUInt16(16))
                    .addField("fUint8", Value.withUInt8(8))
                    .addField("fTimestamp", Value.withTimestamp(time.getTimeInMillis()))
                    .addField("fVarbinary", Value.withVarbinary("test".getBytes(StandardCharsets.UTF_8))).build();
        }
        return builder.build();
    }

    @Ignore
    @Test
    public void holdConnectionTest() throws ExecutionException, InterruptedException {
        comprehensiveTest();
        for (int i = 0; i < 1000; i++) {
            Thread.sleep(1000);
        }
    }

    @SuppressWarnings("all")
    @Ignore
    @Test
    public void loopToWriteTest() throws Exception {
        final int concurrence = 32;
        final List<CompletableFuture<Result<WriteOk, Err>>> fs = new ArrayList<>();
        for (;;) {
            try {
                for (int i = 0; i < concurrence; i++) {
                    fs.add(writeAsync(Calendar.getInstance(), 32));
                }

                for (final CompletableFuture<Result<WriteOk, Err>> f : fs) {
                    f.get();
                }
            } catch (final Exception e) {
                e.printStackTrace();
            } finally {
                fs.clear();
            }
        }
    }

    @Ignore
    @Test
    public void streamWriteTest() {
        final StreamWriteBuf<Point, WriteOk> writeBuf = this.client.streamWrite(TEST_TABLE_NAME);
        final CompletableFuture<WriteOk> future = writeBuf.write(makePoints(Calendar.getInstance(), 2)) //
                .write(makePoints(Calendar.getInstance(), 3)) //
                .flush() //
                .writeAndFlush(makePoints(Calendar.getInstance(), 10)) //
                .write(makePoints(Calendar.getInstance(), 10)) //
                .writeAndFlush(makePoints(Calendar.getInstance(), 10)) //
                .completed();

        Assert.assertEquals(35, future.join().getSuccess());
    }

    @Ignore
    @Test
    public void streamQueryTest() {
        final Calendar time = Calendar.getInstance();
        final StreamWriteBuf<Point, WriteOk> writeBuf = this.client.streamWrite(TEST_TABLE_NAME);
        for (int i = 0; i < 1000; i++) {
            time.add(Calendar.MILLISECOND, 1);
            writeBuf.writeAndFlush(makePoints(time, 1));
        }
        final CompletableFuture<WriteOk> writeOk = writeBuf.completed();
        Assert.assertEquals(1000, writeOk.join().getSuccess());

        final SqlQueryRequest req = SqlQueryRequest.newBuilder().sql("select * from %s", TEST_TABLE_NAME).build();
        final Iterator<Row> it = this.client.blockingStreamSqlQuery(req, 3, TimeUnit.SECONDS);

        int i = 0;
        while (it.hasNext()) {
            LOG.info("The {} row, timestamp={}", ++i, it.next().getColumnValue("ts"));
        }

        Assert.assertEquals(1000, i);
    }
}

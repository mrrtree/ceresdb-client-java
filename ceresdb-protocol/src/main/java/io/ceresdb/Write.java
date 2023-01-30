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

import java.util.concurrent.CompletableFuture;

import io.ceresdb.models.Err;
import io.ceresdb.models.Point;
import io.ceresdb.models.Result;
import io.ceresdb.models.WriteOk;
import io.ceresdb.models.WriteRequest;
import io.ceresdb.rpc.Context;
import io.ceresdb.util.StreamWriteBuf;

/**
 * CeresDB write API. Writes the streaming data to the database, support
 * failed retries.
 *
 * @author xvyang.xy
 */
public interface Write {

    /**
     * @see #write(WriteRequest, Context)
     */
    default CompletableFuture<Result<WriteOk, Err>> write(final WriteRequest req) {
        return write(req, Context.newDefault());
    }

    /**
     * Write the data stream to the database.
     *
     * @param req write request
     * @param ctx the invoked context
     * @return write result
     */
    CompletableFuture<Result<WriteOk, Err>> write(final WriteRequest req, final Context ctx);

    /**
     * @see #streamWrite(String, Context)
     */
    default StreamWriteBuf<Point, WriteOk> streamWrite(final String table) {
        return streamWrite(table, Context.newDefault());
    }

    /**
     * Executes a stream-write-call, returns a write request observer for streaming-write.
     *
     * @param table  the table to write
     * @param ctx    the invoked context
     * @return a write request observer for streaming-write
     */
    StreamWriteBuf<Point, WriteOk> streamWrite(final String table, final Context ctx);
}

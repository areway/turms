/*
 * Copyright (C) 2019 The Turms Project
 * https://github.com/turms-im/turms
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.turms.server.common.cluster.service.rpc;

import im.turms.server.common.cluster.service.connection.TurmsConnection;
import im.turms.server.common.cluster.service.rpc.codec.RpcFrameEncoder;
import im.turms.server.common.cluster.service.rpc.dto.RpcRequest;
import im.turms.server.common.cluster.service.rpc.dto.RpcResponse;
import im.turms.server.common.util.MapUtil;
import io.netty.buffer.ByteBuf;
import io.netty.util.IllegalReferenceCountException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.channel.ChannelOperations;

import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author James Chen
 */
@Log4j2
public final class RpcEndpoint {

    private static final int EXPECTED_MAX_QPS = 1000;
    private static final int EXPECTED_AVERAGE_RTT = 10;
    private static final int INITIAL_CAPACITY_PERCENTAGE = 10;

    @Getter
    private final String nodeId;
    @Getter
    private final TurmsConnection connection;
    private final Map<Integer, Sinks.One<?>> pendingRequestMap =
            new ConcurrentHashMap<>(MapUtil.getCapability(
                    (int) (EXPECTED_MAX_QPS * EXPECTED_AVERAGE_RTT * (INITIAL_CAPACITY_PERCENTAGE / 100F))));

    public RpcEndpoint(String nodeId, TurmsConnection connection) {
        this.nodeId = nodeId;
        this.connection = connection;
    }

    // Handle Request

    /**
     * Accept requestBody of ByteBuf so that we can send the same buffer to multiple peers
     *
     * @implNote The method ensures requestBody will be released by 1
     */
    public <T> Mono<T> sendRequest(RpcRequest<T> request, ByteBuf requestBody) {
        ChannelOperations<?, ?> conn = connection.getConnection();
        if (conn.isDisposed()) {
            requestBody.release();
            return Mono.error(new ClosedChannelException());
        }
        if (requestBody.refCnt() == 0) {
            return Mono.error(new IllegalReferenceCountException("The request body has been released"));
        }
        Sinks.One<T> sink = Sinks.one();
        while (true) {
            int requestId = generateRandomId();
            Sinks.One<?> previous = pendingRequestMap.putIfAbsent(requestId, sink);
            if (previous != null) {
                continue;
            }
            request.setRequestId(requestId);
            ByteBuf buffer;
            try {
                buffer = RpcFrameEncoder.INSTANCE.encodeRequest(request, requestBody);
            } catch (Exception e) {
                requestBody.release();
                resolveRequest(requestId, null, new IllegalStateException("Failed to encode request", e));
                break;
            }
            // Note sendObject() should release the buffer no matter it succeeds or fails
            conn.sendObject(buffer)
                    .then()
                    .onErrorResume(t -> {
                        resolveRequest(requestId, null, t);
                        return Mono.empty();
                    })
                    .subscribe();
            break;
        }
        return sink.asMono();
    }

    private int generateRandomId() {
        int id;
        do {
            id = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        } while (pendingRequestMap.containsKey(id));
        return id;
    }

    // Handle Response

    public void handleResponse(RpcResponse response) {
        resolveRequest(response.requestId(), response.result(), response.exception());
    }

    private <T> void resolveRequest(int requestId, T response, Throwable error) {
        Sinks.One<T> sink = (Sinks.One<T>) pendingRequestMap.remove(requestId);
        if (sink == null) {
            log.warn("No sink of the request with ID {} is found for the response: " + response, requestId);
            return;
        }
        if (error == null) {
            sink.tryEmitValue(response);
        } else {
            sink.tryEmitError(error);
        }
    }

}

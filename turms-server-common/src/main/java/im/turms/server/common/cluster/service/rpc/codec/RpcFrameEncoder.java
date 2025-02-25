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

package im.turms.server.common.cluster.service.rpc.codec;

import im.turms.server.common.cluster.service.codec.codec.Codec;
import im.turms.server.common.cluster.service.codec.codec.CodecPool;
import im.turms.server.common.cluster.service.codec.exception.CodecNotFoundException;
import im.turms.server.common.cluster.service.rpc.dto.RpcRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.IllegalReferenceCountException;

/**
 * @author James Chen
 */
public final class RpcFrameEncoder extends LengthFieldPrepender {

    public static final int LENGTH_FIELD_LENGTH = 2;

    public static final RpcFrameEncoder INSTANCE = new RpcFrameEncoder();

    private RpcFrameEncoder() {
        super(LENGTH_FIELD_LENGTH);
    }

    public <T> ByteBuf encode(int requestId, T value) {
        if (requestId < 0) {
            throw new IllegalArgumentException("requestId must be larger than 0. Actual: " + requestId);
        }
        Class<?> valueClass = value.getClass();
        Codec<T> valueCodec = CodecPool.getCodec(valueClass);
        if (valueCodec == null) {
            throw new CodecNotFoundException("No codec found for the class: " + valueClass.getName());
        }
        ByteBuf byteBufToComposite = valueCodec.byteBufToComposite(value);
        if (byteBufToComposite != null && byteBufToComposite.refCnt() == 0) {
            throw new IllegalReferenceCountException("byteBufToComposite of the data has been released: " + value);
        }
        int codecId = valueCodec.getCodecId().getId();
        int initialCapacity = valueCodec.initialCapacity(value);
        initialCapacity = initialCapacity > -1
                ? initialCapacity + Short.BYTES + Integer.BYTES
                : 128;
        ByteBuf outputBuffer = PooledByteBufAllocator.DEFAULT
                .directBuffer(initialCapacity)
                .writeShort(codecId)
                .writeInt(requestId);
        valueCodec.write(outputBuffer, value);
        if (byteBufToComposite != null) {
            outputBuffer = PooledByteBufAllocator.DEFAULT
                    .compositeDirectBuffer(2)
                    .addComponents(true, outputBuffer, byteBufToComposite);
        }
        return outputBuffer;
    }

    /**
     * @param requestBody includes the trace ID
     */
    public ByteBuf encodeRequest(RpcRequest<?> request, ByteBuf requestBody) {
        ByteBuf header = PooledByteBufAllocator.DEFAULT
                .directBuffer(Short.BYTES + Integer.BYTES)
                .writeShort(getCodec(request).getCodecId().getId())
                .writeInt(request.getRequestId());
        return PooledByteBufAllocator.DEFAULT
                .compositeDirectBuffer(2)
                .addComponents(true, header, requestBody);
    }

    private <T> Codec<T> getCodec(T value) {
        Class<?> valueClass = value.getClass();
        Codec<T> valueCodec = CodecPool.getCodec(valueClass);
        if (valueCodec == null) {
            throw new CodecNotFoundException("No codec found for the class: " + valueClass.getName());
        }
        return valueCodec;
    }

}

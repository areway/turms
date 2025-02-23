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

package im.turms.server.common.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

import java.nio.charset.StandardCharsets;

/**
 * @author James Chen
 */
public final class ByteBufUtil {

    private static final int BYTE_CACHE_SIZE = Byte.MAX_VALUE + 1;
    private static final int INTEGER_CACHE_SIZE = Byte.MAX_VALUE + 1;
    private static final ByteBuf[] BYTE_CACHE;
    private static final ByteBuf[] INTEGER_CACHE;

    static {
        BYTE_CACHE = new ByteBuf[BYTE_CACHE_SIZE];
        INTEGER_CACHE = new ByteBuf[INTEGER_CACHE_SIZE];
        for (int i = 0; i < BYTE_CACHE_SIZE; i++) {
            BYTE_CACHE[i] = Unpooled.unreleasableBuffer(Unpooled.directBuffer(Byte.BYTES).writeByte(i));
        }
        for (int i = 0; i < INTEGER_CACHE_SIZE; i++) {
            INTEGER_CACHE[i] = Unpooled.unreleasableBuffer(Unpooled.directBuffer(Integer.BYTES).writeInt(i));
        }
    }

    private ByteBufUtil() {
    }

    public static ByteBuf getByteBuffer(int value) {
        if (0 <= value && value < BYTE_CACHE_SIZE) {
            return BYTE_CACHE[value];
        }
        return UnpooledByteBufAllocator.DEFAULT.directBuffer(Byte.BYTES).writeByte(value);
    }

    public static ByteBuf getIntegerBuffer(int value) {
        if (0 <= value && value < INTEGER_CACHE_SIZE) {
            return INTEGER_CACHE[value];
        }
        return UnpooledByteBufAllocator.DEFAULT.directBuffer(Integer.BYTES).writeInt(value);
    }

    public static ByteBuf getLongBuffer(long value) {
        return UnpooledByteBufAllocator.DEFAULT.directBuffer(Long.BYTES).writeLong(value);
    }

    public static ByteBuf getUnreleasableDirectBuffer(byte[] bytes) {
        ByteBuf buffer = Unpooled.directBuffer(bytes.length)
                .writeBytes(bytes);
        return Unpooled.unreleasableBuffer(buffer);
    }

    public static ByteBuf obj2Buffer(Object obj) {
        if (obj instanceof ByteBuf element) {
            return element;
        }
        if (obj instanceof Byte element) {
            return getByteBuffer(element.intValue());
        }
        if (obj instanceof Short element) {
            return UnpooledByteBufAllocator.DEFAULT.directBuffer(Short.BYTES).writeShort(element);
        }
        if (obj instanceof Integer element) {
            return getIntegerBuffer(element);
        }
        if (obj instanceof Long element) {
            return UnpooledByteBufAllocator.DEFAULT.directBuffer(Long.BYTES).writeLong(element);
        }
        if (obj instanceof String element) {
            byte[] bytes = element.getBytes(StandardCharsets.UTF_8);
            return UnpooledByteBufAllocator.DEFAULT.directBuffer(bytes.length).writeBytes(bytes);
        }
        if (obj instanceof Float element) {
            return UnpooledByteBufAllocator.DEFAULT.directBuffer(Float.BYTES).writeFloat(element);
        }
        if (obj instanceof Double element) {
            return UnpooledByteBufAllocator.DEFAULT.directBuffer(Double.BYTES).writeDouble(element);
        }
        if (obj instanceof Character element) {
            return UnpooledByteBufAllocator.DEFAULT.directBuffer(Character.BYTES).writeChar(element);
        }
        if (obj instanceof Boolean element) {
            return getByteBuffer(element ? 1 : 0);
        }
        throw new IllegalArgumentException("Cannot serialize the unknown value: " + obj);
    }

    public static ByteBuf[] objs2Buffers(Object... objs) {
        ByteBuf[] buffers = new ByteBuf[objs.length];
        for (int i = 0; i < objs.length; i++) {
            buffers[i] = ByteBufUtil.obj2Buffer(objs[i]);
        }
        return buffers;
    }


    public static void ensureReleased(ByteBuf buffer) {
        int refCnt = buffer.refCnt();
        if (refCnt > 0) {
            buffer.release(refCnt);
        }
    }

    public static void ensureReleased(ByteBuf[] buffers) {
        for (ByteBuf buffer : buffers) {
            ensureReleased(buffer);
        }
    }

}

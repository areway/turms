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

package im.turms.server.common.redis.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;

import java.nio.ByteBuffer;

/**
 * @author James Chen
 */
public class SessionKeyCodec implements TurmsRedisCodec<Long> {

    @Override
    public ByteBuf encode(Long value) {
        return UnpooledByteBufAllocator.DEFAULT.directBuffer(Long.BYTES).writeLong(value);
    }

    @Override
    public Long decode(ByteBuffer in) {
        return in.getLong();
    }

}

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

import im.turms.common.constant.DeviceType;
import im.turms.server.common.bo.session.UserSessionId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;

import java.nio.ByteBuffer;

/**
 * @author James Chen
 */
public class GeoUserSessionIdCodec implements TurmsRedisCodec<UserSessionId> {

    @Override
    public ByteBuf encode(UserSessionId sessionId) {
        return UnpooledByteBufAllocator.DEFAULT.directBuffer(Long.BYTES + Byte.BYTES)
                .writeLong(sessionId.getUserId())
                .writeByte(sessionId.getDeviceType().getNumber());
    }

    @Override
    public UserSessionId decode(ByteBuffer in) {
        long userId = in.getLong();
        byte value = in.get();
        DeviceType deviceType = DeviceType.forNumber(value);
        if (deviceType == null) {
            throw new IllegalArgumentException("Cannot parse " + value + "to DeviceType");
        }
        return new UserSessionId(userId, deviceType);
    }

}

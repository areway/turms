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

package im.turms.gateway.access.tcp.handler;

import im.turms.gateway.access.common.handler.ServerAvailabilityHandler;
import im.turms.server.common.manager.ServerStatusManager;
import io.netty.channel.Channel;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import reactor.netty.Connection;

/**
 * @author James Chen
 */
public class TcpHandlerConfig {

    private final ServerAvailabilityHandler serverAvailabilityHandler;

    public TcpHandlerConfig(ServerStatusManager serverStatusManager) {
        serverAvailabilityHandler = new ServerAvailabilityHandler(serverStatusManager);
    }

    public void configureChannel(Channel channel) {
        channel.pipeline().addFirst("serverAvailabilityHandler", serverAvailabilityHandler);
    }

    public void configureConnection(Connection connection) {
        // Inbound
        connection.addHandlerLast("protobufFrameDecoder", new ProtobufVarint32FrameDecoder());

        // Outbound
        connection.addHandlerLast("protobufFrameEncoder", new ProtobufVarint32LengthFieldPrepender());
    }

}
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

package im.turms.gateway.access.common.controller;

import im.turms.common.constant.DeviceType;
import im.turms.common.model.dto.notification.TurmsNotification;
import im.turms.common.model.dto.request.TurmsRequest;
import im.turms.gateway.access.common.model.UserSessionWrapper;
import im.turms.gateway.access.tcp.dto.RequestHandlerResult;
import im.turms.gateway.access.tcp.util.TurmsNotificationUtil;
import im.turms.gateway.logging.ClientApiLogging;
import im.turms.gateway.pojo.bo.session.UserSession;
import im.turms.gateway.pojo.dto.SimpleTurmsRequest;
import im.turms.gateway.service.mediator.ServiceMediator;
import im.turms.gateway.util.TurmsRequestUtil;
import im.turms.server.common.constant.TurmsStatusCode;
import im.turms.server.common.dto.ServiceRequest;
import im.turms.server.common.exception.ThrowableInfo;
import im.turms.server.common.factory.NotificationFactory;
import im.turms.server.common.logging.LoggingRequestUtil;
import im.turms.server.common.logging.RequestLoggingContext;
import im.turms.server.common.manager.ServerStatusManager;
import im.turms.server.common.property.TurmsPropertiesManager;
import im.turms.server.common.property.env.gateway.clientapi.ClientApiLoggingProperties;
import im.turms.server.common.property.env.service.env.clientapi.property.LoggingRequestProperties;
import im.turms.server.common.tracing.TracingCloseableContext;
import im.turms.server.common.tracing.TracingContext;
import im.turms.server.common.util.ProtoUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

import static im.turms.common.model.dto.request.TurmsRequest.KindCase.CREATE_SESSION_REQUEST;
import static im.turms.common.model.dto.request.TurmsRequest.KindCase.DELETE_SESSION_REQUEST;
import static im.turms.server.common.constant.CommonMetricsConstant.CLIENT_REQUEST_NAME;
import static im.turms.server.common.constant.CommonMetricsConstant.CLIENT_REQUEST_TAG_TYPE;

/**
 * @author James Chen
 */
@Component
@Log4j2
public class UserRequestDispatcher {

    private static final ByteBuf HEARTBEAT_RESPONSE_SUCCESS = new EmptyByteBuf(UnpooledByteBufAllocator.DEFAULT);
    private static final ByteBuf HEARTBEAT_RESPONSE_UPDATE_NON_EXISTING_SESSION_HEARTBEAT;
    private static final ByteBuf HEARTBEAT_RESPONSE_SERVER_UNAVAILABLE;

    private static final long HEARTBEAT_FAILURE_REQUEST_ID = -100;

    static {
        TurmsNotification notification = NotificationFactory
                .create(TurmsStatusCode.UPDATE_NON_EXISTING_SESSION_HEARTBEAT, HEARTBEAT_FAILURE_REQUEST_ID);
        HEARTBEAT_RESPONSE_UPDATE_NON_EXISTING_SESSION_HEARTBEAT = Unpooled.unreleasableBuffer(ProtoUtil.getDirectByteBuffer(notification));
        notification = NotificationFactory.create(TurmsStatusCode.SERVER_UNAVAILABLE, HEARTBEAT_FAILURE_REQUEST_ID);
        HEARTBEAT_RESPONSE_SERVER_UNAVAILABLE = Unpooled.unreleasableBuffer(ProtoUtil.getDirectByteBuffer(notification));
    }

    private final SessionController sessionController;
    private final ServiceMediator serviceMediator;
    private final ServerStatusManager serverStatusManager;
    private final Map<TurmsRequest.KindCase, LoggingRequestProperties> supportedLoggingRequestProperties;

    public UserRequestDispatcher(SessionController sessionController,
                                 ServiceMediator serviceMediator,
                                 ServerStatusManager serverStatusManager,
                                 TurmsPropertiesManager propertiesManager) {
        this.sessionController = sessionController;
        this.serviceMediator = serviceMediator;
        this.serverStatusManager = serverStatusManager;
        ClientApiLoggingProperties loggingProperties = propertiesManager.getLocalProperties().getGateway().getClientApi().getLogging();
        supportedLoggingRequestProperties = LoggingRequestUtil.getSupportedLoggingRequestProperties(
                loggingProperties.getIncludedRequestCategories(),
                loggingProperties.getIncludedRequests(),
                loggingProperties.getExcludedRequestCategories(),
                loggingProperties.getExcludedRequestTypes());
    }

    /**
     * @implNote 1. If a throwable instance is thrown due to the failure of handling the client request,
     * the method should recover it to TurmsNotification.
     * In other words, the method should never return MonoError, and it should be considered as a bug if it occurs.
     * 2. The method ensures serviceRequestBuffer will be released by 1
     */
    public Mono<ByteBuf> handleRequest(UserSessionWrapper sessionWrapper, ByteBuf serviceRequestBuffer) {
        // Check if it's a heartbeat request
        if (!serviceRequestBuffer.isReadable()) {
            serviceRequestBuffer.release();
            if (!serverStatusManager.isActive()) {
                return Mono.just(HEARTBEAT_RESPONSE_SERVER_UNAVAILABLE);
            }
            return handleHeartbeatRequest(sessionWrapper);
        }
        // Parse and handle service requests
        long requestTime = System.currentTimeMillis();
        int requestSize = serviceRequestBuffer.readableBytes();
        SimpleTurmsRequest request = TurmsRequestUtil.parseSimpleRequest(serviceRequestBuffer.nioBuffer());
        TurmsRequest.KindCase requestType = request.getType();
        TracingContext tracingContext = supportsTracing(requestType) ? new TracingContext() : TracingContext.NOOP;
        return handleServiceRequest(sessionWrapper, request, serviceRequestBuffer, tracingContext)
                // Metrics and logging
                .name(CLIENT_REQUEST_NAME)
                .tag(CLIENT_REQUEST_TAG_TYPE, requestType.name())
                .metrics()
                .onErrorResume(throwable -> {
                    ThrowableInfo info = ThrowableInfo.get(throwable);
                    if (info.getCode().isServerError()) {
                        tracingContext.updateMdc();
                        log.error("Failed to handle the service request: {}", request, throwable);
                    }
                    return Mono.just(NotificationFactory.create(info, request.getRequestId()));
                })
                .map(notification -> {
                    TurmsRequest.KindCase type = request.getType();
                    // TODO: exclude the error because the server is inactive
                    if (TurmsStatusCode.isServerError(notification.getCode())
                            || LoggingRequestUtil.shouldLog(type, supportedLoggingRequestProperties)) {
                        try (TracingCloseableContext ignored = tracingContext.asCloseable()) {
                            UserSession userSession = sessionWrapper.getUserSession();
                            Long userId = null;
                            DeviceType deviceType = null;
                            if (userSession != null) {
                                userId = userSession.getUserId();
                                deviceType = userSession.getDeviceType();
                            }
                            ClientApiLogging.log(sessionWrapper.getIp().getAddress().getHostAddress(),
                                    userId,
                                    deviceType,
                                    request.getRequestId(),
                                    type,
                                    requestSize,
                                    requestTime,
                                    notification,
                                    System.currentTimeMillis() - requestTime);
                        }
                    }
                    return ProtoUtil.getDirectByteBuffer(notification);
                })
                .contextWrite(context -> {
                    RequestLoggingContext loggingContext = context.get(RequestLoggingContext.CTX_KEY_NAME);
                    loggingContext.setTracingContext(tracingContext);
                    return context;
                });
    }

    /**
     * The method ensures serviceRequestBuffer will be released by 1
     */
    public Mono<TurmsNotification> handleServiceRequest(UserSessionWrapper sessionWrapper,
                                                        SimpleTurmsRequest request,
                                                        ByteBuf serviceRequestBuffer,
                                                        TracingContext tracingContext) {
        try {
            // Validate
            long requestId = request.getRequestId();
            if (requestId <= 0) {
                TurmsNotification notification = NotificationFactory.create(TurmsStatusCode.INVALID_REQUEST,
                        "The request ID must be greater than 0",
                        requestId);
                return Mono.just(notification);
            }
            // Check server status
            if (!serverStatusManager.isActive()) {
                TurmsNotification notification = NotificationFactory.create(TurmsStatusCode.SERVER_UNAVAILABLE, requestId);
                return Mono.just(notification);
            }
            // Handle the request to get a response
            TurmsRequest.KindCase requestType = request.getType();
            tracingContext.updateMdc();
            return switch (requestType) {
                case CREATE_SESSION_REQUEST -> sessionController
                        .handleCreateSessionRequest(sessionWrapper, request.getCreateSessionRequest())
                        .map(result -> getNotificationFromHandlerResult(result, request.getRequestId()));
                case DELETE_SESSION_REQUEST -> sessionController.handleDeleteSessionRequest(sessionWrapper);
                default -> {
                    serviceRequestBuffer.retain();
                    yield handleServiceRequestForTurms(sessionWrapper, request, serviceRequestBuffer);
                }
            };
        } catch (Exception e) {
            TurmsNotification notification = NotificationFactory
                    .create(ThrowableInfo.get(e), request.getRequestId());
            return Mono.just(notification);
        } finally {
            serviceRequestBuffer.release();
            tracingContext.clearMdc();
        }
    }

    private Mono<ByteBuf> handleHeartbeatRequest(UserSessionWrapper sessionWrapper) {
        UserSession session = sessionWrapper.getUserSession();
        ByteBuf data;
        if (session != null) {
            serviceMediator.processHeartbeatRequest(session);
            data = HEARTBEAT_RESPONSE_SUCCESS;
        } else {
            data = HEARTBEAT_RESPONSE_UPDATE_NON_EXISTING_SESSION_HEARTBEAT;
        }
        return Mono.just(data);
    }

    private Mono<TurmsNotification> handleServiceRequestForTurms(UserSessionWrapper sessionWrapper,
                                                                 SimpleTurmsRequest request,
                                                                 ByteBuf serviceRequestBuffer) {
        UserSession session = sessionWrapper.getUserSession();
        if (session == null || !session.isOpen()) {
            return Mono.just(TurmsNotificationUtil.sessionClosed(request.getRequestId()));
        }
        ServiceRequest serviceRequest = new ServiceRequest(
                sessionWrapper.getIp().getAddress().getAddress(),
                session.getUserId(),
                session.getDeviceType(),
                request.getRequestId(),
                request.getType(),
                serviceRequestBuffer);
        return serviceMediator.processServiceRequest(serviceRequest);
    }

    private TurmsNotification getNotificationFromHandlerResult(RequestHandlerResult result, long requestId) {
        TurmsNotification.Builder builder = TurmsNotification.newBuilder()
                .setRequestId(requestId)
                .setCode(result.getCode().getBusinessCode());
        String reason = result.getReason();
        if (reason != null) {
            builder.setReason(reason);
        }
        return builder.build();
    }

    /**
     * @implNote Though the requests for gateway don't need trace currently,
     * but we may need tracing in the future so we use a mechanism to support
     * tracing any requests if we want
     */
    private boolean supportsTracing(TurmsRequest.KindCase requestType) {
        return requestType != CREATE_SESSION_REQUEST && requestType != DELETE_SESSION_REQUEST;
    }

}
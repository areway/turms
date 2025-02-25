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

package im.turms.server.common.cluster.service.config;

import com.mongodb.client.model.changestream.FullDocument;
import im.turms.server.common.cluster.node.NodeType;
import im.turms.server.common.cluster.service.ClusterService;
import im.turms.server.common.cluster.service.codec.CodecService;
import im.turms.server.common.cluster.service.config.domain.property.CommonProperties;
import im.turms.server.common.cluster.service.config.domain.property.SharedClusterProperties;
import im.turms.server.common.cluster.service.connection.ConnectionService;
import im.turms.server.common.cluster.service.discovery.DiscoveryService;
import im.turms.server.common.cluster.service.idgen.IdService;
import im.turms.server.common.cluster.service.rpc.RpcService;
import im.turms.server.common.mongo.exception.DuplicateKeyException;
import im.turms.server.common.mongo.operation.option.Filter;
import im.turms.server.common.mongo.operation.option.Update;
import im.turms.server.common.property.TurmsProperties;
import im.turms.server.common.property.TurmsPropertiesManager;
import im.turms.server.common.property.env.gateway.GatewayProperties;
import im.turms.server.common.property.env.service.ServiceProperties;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import static im.turms.server.common.cluster.service.config.domain.property.SharedClusterProperties.getCommonProperties;

/**
 * @author James Chen
 */
@Log4j2
public class SharedPropertyService implements ClusterService {

    private final String clusterId;
    private final NodeType nodeType;

    private final TurmsPropertiesManager turmsPropertiesManager;
    private SharedClusterProperties sharedClusterProperties;
    private SharedConfigService sharedConfigService;

    private final List<Consumer<TurmsProperties>> propertiesChangeListeners = new LinkedList<>();

    public SharedPropertyService(String clusterId,
                                 NodeType nodeType,
                                 TurmsPropertiesManager turmsPropertiesManager) {
        this.clusterId = clusterId;
        this.nodeType = nodeType;
        this.turmsPropertiesManager = turmsPropertiesManager;
    }

    public TurmsProperties getSharedProperties() {
        return sharedClusterProperties.getTurmsProperties();
    }

    @Override
    public void lazyInit(CodecService codecService,
                         ConnectionService connectionService,
                         DiscoveryService discoveryService,
                         IdService idService,
                         RpcService rpcService,
                         SharedConfigService sharedConfigService) {
        this.sharedConfigService = sharedConfigService;
    }

    @Override
    public void start() {
        sharedConfigService.subscribe(SharedClusterProperties.class, FullDocument.UPDATE_LOOKUP)
                .doOnNext(event -> {
                    SharedClusterProperties changedProperties = event.getFullDocument();
                    String changeClusterId = changedProperties != null
                            ? changedProperties.getClusterId()
                            : ChangeStreamUtil.getIdAsString(event.getDocumentKey());
                    if (changeClusterId.equals(clusterId)) {
                        switch (event.getOperationType()) {
                            case INSERT, REPLACE, UPDATE -> {
                                sharedClusterProperties = changedProperties;
                                notifyListeners(sharedClusterProperties.getTurmsProperties());
                            }
                            case INVALIDATE -> {
                                log.warn("The shared properties has been removed from database unexpectedly");
                                initializeSharedProperties().subscribe();
                            }
                            default -> {
                            }
                        }
                    }
                })
                .onErrorContinue(
                        (throwable, o) -> log.error("Error while processing the change stream event of SharedProperties: {}", o, throwable))
                .subscribe();
        initializeSharedProperties().block(Duration.ofMinutes(1));
    }

    /**
     * @implNote We don't support the partial update by {@link java.util.Map} because
     * there is not an efficient way to update nested objects of a document in MongoDB.
     */
    public Mono<Void> updateSharedProperties(TurmsProperties turmsProperties) {
        log.info("Share new turms properties to all members");
        SharedClusterProperties clusterProperties = getClusterProperties(sharedClusterProperties, turmsProperties);
        Date now = new Date();
        Filter filter = Filter.newBuilder(2)
                .eq("_id", clusterId)
                .lt(SharedClusterProperties.Fields.lastUpdatedTime, now);
        Update update = Update.newBuilder(3)
                .set(SharedClusterProperties.Fields.commonProperties, clusterProperties.getCommonProperties());
        if (clusterProperties.getGatewayProperties() != null) {
            update.set(SharedClusterProperties.Fields.gatewayProperties, clusterProperties.getGatewayProperties());
        }
        if (clusterProperties.getServiceProperties() != null) {
            update.set(SharedClusterProperties.Fields.serviceProperties, clusterProperties.getServiceProperties());
        }
        return sharedConfigService.upsert(filter, update, clusterProperties)
                .doOnError(e -> log.error("Failed to share new turms properties", e))
                .then(Mono.defer(() -> {
                    sharedClusterProperties = clusterProperties;
                    log.info("Turms properties have been shared");
                    return Mono.empty();
                }));
    }

    public void addListeners(Consumer<TurmsProperties> listener) {
        propertiesChangeListeners.add(listener);
    }

    private void notifyListeners(TurmsProperties properties) {
        for (Consumer<TurmsProperties> listener : propertiesChangeListeners) {
            try {
                listener.accept(properties);
            } catch (Exception e) {
                log.error("The properties listener {} failed to handle the new properties", listener.getClass().getName(), e);
            }
        }
    }

    private Mono<SharedClusterProperties> initializeSharedProperties() {
        log.info("Trying to get shared properties");
        TurmsProperties localProperties = turmsPropertiesManager.getLocalProperties();
        SharedClusterProperties clusterProperties = new SharedClusterProperties(clusterId, localProperties, new Date());
        if (nodeType == NodeType.GATEWAY) {
            clusterProperties.setServiceProperties(null);
        } else {
            clusterProperties.setGatewayProperties(null);
        }
        return findAndUpdatePropertiesByNodeType(clusterProperties)
                .switchIfEmpty(Mono.defer(() -> sharedConfigService.insert(clusterProperties)))
                .onErrorResume(DuplicateKeyException.class, e -> findAndUpdatePropertiesByNodeType(clusterProperties))
                .doOnSuccess(properties -> {
                    sharedClusterProperties = properties;
                    log.info("Shared properties were retrieved successfully");
                });
    }

    private Mono<SharedClusterProperties> findAndUpdatePropertiesByNodeType(SharedClusterProperties clusterProperties) {
        Filter filter = Filter.newBuilder(2)
                .eq("_id", clusterId);
        return sharedConfigService.findOne(SharedClusterProperties.class, filter)
                .flatMap(properties -> {
                    if (nodeType == NodeType.GATEWAY) {
                        if (properties.getGatewayProperties() == null) {
                            filter.eq(SharedClusterProperties.Fields.gatewayProperties, null);
                            Update update = Update.newBuilder()
                                    .set(SharedClusterProperties.Fields.gatewayProperties, clusterProperties.getGatewayProperties());
                            return sharedConfigService.updateOne(SharedClusterProperties.class, filter, update)
                                    .map(result -> {
                                        if (result.getModifiedCount() > 0) {
                                            properties.setGatewayProperties(clusterProperties.getGatewayProperties());
                                            return properties;
                                        } else {
                                            throw new IllegalStateException("Failed to update the cluster properties");
                                        }
                                    });
                        }
                    } else {
                        if (properties.getServiceProperties() == null) {
                            filter.eq(SharedClusterProperties.Fields.serviceProperties, null);
                            Update update = Update.newBuilder()
                                    .set(SharedClusterProperties.Fields.serviceProperties, clusterProperties.getServiceProperties());
                            return sharedConfigService.updateOne(SharedClusterProperties.class, filter, update)
                                    .map(result -> {
                                        if (result.getModifiedCount() > 0) {
                                            properties.setServiceProperties(clusterProperties.getServiceProperties());
                                            return properties;
                                        } else {
                                            throw new IllegalStateException("Failed to update the cluster properties");
                                        }
                                    });
                        }
                    }
                    return Mono.just(properties);
                });
    }

    private static SharedClusterProperties getClusterProperties(SharedClusterProperties clusterPropertiesSource,
                                                                TurmsProperties turmsProperties) {
        CommonProperties commonProperties = getCommonProperties(turmsProperties);
        GatewayProperties gatewayProperties = turmsProperties.getGateway();
        ServiceProperties serviceProperties = turmsProperties.getService();
        return clusterPropertiesSource.toBuilder()
                .commonProperties(commonProperties)
                .gatewayProperties(gatewayProperties)
                .serviceProperties(serviceProperties)
                .turmsProperties(turmsProperties)
                .build();
    }
}
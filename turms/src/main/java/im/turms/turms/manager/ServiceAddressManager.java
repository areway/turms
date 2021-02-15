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

package im.turms.turms.manager;

import im.turms.server.common.manager.PublicIpManager;
import im.turms.server.common.manager.address.AddressCollection;
import im.turms.server.common.manager.address.AddressCollector;
import im.turms.server.common.manager.address.BaseServiceAddressManager;
import im.turms.server.common.property.TurmsPropertiesManager;
import im.turms.server.common.property.env.common.AdminApiDiscoveryProperties;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.stereotype.Component;

import java.net.UnknownHostException;

/**
 * @author James Chen
 */
@Component
@Log4j2
public class ServiceAddressManager extends BaseServiceAddressManager {

    private AdminApiDiscoveryProperties adminApiDiscoveryProperties;
    private String metricsApiAddress;
    private String adminApiAddress;

    public ServiceAddressManager(
            TurmsPropertiesManager turmsPropertiesManager,
            ServerProperties adminApiServerProperties,
            PublicIpManager publicIpManager) throws UnknownHostException {
        super(publicIpManager);
        updateCollectorAndAddresses(adminApiServerProperties, turmsPropertiesManager.getLocalProperties().getService().getAdminApi().getDiscovery());
        turmsPropertiesManager.addListeners(properties -> {
            AdminApiDiscoveryProperties newAdminApiDiscoveryProperties = properties.getService().getAdminApi().getDiscovery();
            if (!adminApiDiscoveryProperties.equals(newAdminApiDiscoveryProperties)) {
                try {
                    updateCollectorAndAddresses(adminApiServerProperties, newAdminApiDiscoveryProperties);
                } catch (UnknownHostException e) {
                    log.error("Failed to update address collector", e);
                }
                AddressCollection addresses = new AddressCollection(metricsApiAddress, adminApiAddress, null, null, null);
                triggerOnAddressesChangedListeners(addresses);
            }
        });
    }

    @Override
    public String getMetricsApiAddress() {
        return metricsApiAddress;
    }

    @Override
    public String getAdminApiAddress() {
        return adminApiAddress;
    }

    private void updateCollectorAndAddresses(ServerProperties adminApiServerProperties, AdminApiDiscoveryProperties newAdminApiDiscoveryProperties) throws UnknownHostException {
        AddressCollector adminApiAddressesCollector = getAdminApiAddressCollector(adminApiServerProperties, newAdminApiDiscoveryProperties);
        metricsApiAddress = adminApiAddressesCollector.getHttpAddress() + "/actuator";
        adminApiAddress = adminApiAddressesCollector.getHttpAddress();
        adminApiDiscoveryProperties = newAdminApiDiscoveryProperties;
    }

}
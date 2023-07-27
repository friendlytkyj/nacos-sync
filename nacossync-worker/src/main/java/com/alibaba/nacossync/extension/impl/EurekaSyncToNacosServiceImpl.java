/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.alibaba.nacossync.extension.impl;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacossync.cache.SkyWalkerCacheServices;
import com.alibaba.nacossync.constant.ClusterTypeEnum;
import com.alibaba.nacossync.constant.MetricsStatisticsType;
import com.alibaba.nacossync.constant.SkyWalkerConstants;
import com.alibaba.nacossync.extension.SyncService;
import com.alibaba.nacossync.extension.annotation.NacosSyncService;
import com.alibaba.nacossync.extension.eureka.EurekaNamingService;
import com.alibaba.nacossync.extension.event.SpecialSyncEventBus;
import com.alibaba.nacossync.extension.holder.EurekaServerHolder;
import com.alibaba.nacossync.extension.holder.NacosServerHolder;
import com.alibaba.nacossync.monitor.MetricsManager;
import com.alibaba.nacossync.pojo.model.TaskDO;
import com.alibaba.nacossync.util.NacosUtils;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.shared.Application;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.nacossync.util.DubboConstants.ALL_SERVICE_NAME_PATTERN;

/**
 * eureka
 *
 * @author paderlol
 * @date: 2018-12-31 16:25
 */
@Slf4j
@NacosSyncService(sourceCluster = ClusterTypeEnum.EUREKA, destinationCluster = ClusterTypeEnum.NACOS)
public class EurekaSyncToNacosServiceImpl implements SyncService {

    private final MetricsManager metricsManager;

    private final EurekaServerHolder eurekaServerHolder;
    private final SkyWalkerCacheServices skyWalkerCacheServices;

    private final NacosServerHolder nacosServerHolder;

    private final SpecialSyncEventBus specialSyncEventBus;

    @Autowired
    public EurekaSyncToNacosServiceImpl(EurekaServerHolder eurekaServerHolder,
        SkyWalkerCacheServices skyWalkerCacheServices, NacosServerHolder nacosServerHolder,
        SpecialSyncEventBus specialSyncEventBus, MetricsManager metricsManager) {
        this.eurekaServerHolder = eurekaServerHolder;
        this.skyWalkerCacheServices = skyWalkerCacheServices;
        this.nacosServerHolder = nacosServerHolder;
        this.specialSyncEventBus = specialSyncEventBus;
        this.metricsManager = metricsManager;
    }

    @Override
    public boolean delete(TaskDO taskDO) {

        try {
            specialSyncEventBus.unsubscribe(taskDO);

            EurekaNamingService eurekaNamingService = eurekaServerHolder.get(taskDO.getSourceClusterId());
            NamingService destNamingService = nacosServerHolder.get(taskDO.getDestClusterId());

            List<InstanceInfo> eurekaInstances = eurekaNamingService.getApplications(taskDO.getServiceName());
            deleteAllInstanceFromEureka(taskDO, destNamingService, eurekaInstances);

        } catch (Exception e) {
            log.error("delete a task from eureka to nacos was failed, taskId:{}", taskDO.getTaskId(), e);
            metricsManager.recordError(MetricsStatisticsType.DELETE_ERROR);
            return false;
        }
        return true;
    }

    @Override
    public boolean sync(TaskDO taskDO) {
        try {

            EurekaNamingService eurekaNamingService = eurekaServerHolder.get(taskDO.getSourceClusterId());
            NamingService destNamingService = nacosServerHolder.get(taskDO.getDestClusterId());

            registerAllInstances(taskDO, destNamingService);

            specialSyncEventBus.subscribe(taskDO, this::sync);
        } catch (Exception e) {
            log.error("sync task from eureka to nacos was failed, taskId:{}", taskDO.getTaskId(), e);
            metricsManager.recordError(MetricsStatisticsType.SYNC_ERROR);
            return false;
        }
        return true;
    }

    private void registerAllInstances(TaskDO taskDO, NamingService destNamingService) throws Exception {
        EurekaNamingService eurekaNamingService = eurekaServerHolder.get(taskDO.getSourceClusterId());
        if (!ALL_SERVICE_NAME_PATTERN.equals(taskDO.getServiceName())) {
            registerALLInstances0(taskDO, destNamingService, eurekaNamingService, taskDO.getServiceName());
        } else {
            // 同步全部
            List<Application> applications = eurekaNamingService.getApplications();
            for (Application application : applications) {
                registerALLInstances0(taskDO, destNamingService, eurekaNamingService, StringUtils.lowerCase(application.getName()));
            }
        }
    }

    private void registerALLInstances0(TaskDO taskDO, NamingService destNamingService, EurekaNamingService eurekaNamingService,
                                       String serviceName) throws Exception {
        List<InstanceInfo> eurekaInstances = eurekaNamingService.getApplications(serviceName);
        List<Instance> nacosInstances = destNamingService.getAllInstances(serviceName,
                NacosUtils.getGroupNameOrDefault(taskDO.getGroupName()));

        if (CollectionUtils.isEmpty(eurekaInstances)) {
            // Clear all instance from Nacos
            deleteAllInstance(taskDO, destNamingService, nacosInstances, serviceName);
        } else {
            if (!CollectionUtils.isEmpty(nacosInstances)) {
                // Remove invalid instance from Nacos
                removeInvalidInstance(taskDO, destNamingService, eurekaInstances, nacosInstances, serviceName);
            }
            addValidInstance(taskDO, destNamingService, eurekaInstances, serviceName);
        }
    }

    private void addValidInstance(TaskDO taskDO, NamingService destNamingService, List<InstanceInfo> eurekaInstances, String serviceName)
        throws NacosException {
        for (InstanceInfo instance : eurekaInstances) {
            if (needSync(instance.getMetadata())) {
                log.info("Add service instance from Eureka, serviceName={}, Ip={}, port={}",
                    instance.getAppName(), instance.getIPAddr(), instance.getPort());
                destNamingService.registerInstance(serviceName,
                    NacosUtils.getGroupNameOrDefault(taskDO.getGroupName()), buildSyncInstance(instance,
                        taskDO));
            }
        }
    }

    private void deleteAllInstanceFromEureka(TaskDO taskDO, NamingService destNamingService,
        List<InstanceInfo> eurekaInstances)
        throws NacosException {
        if (CollectionUtils.isEmpty(eurekaInstances)) {
            return;
        }
        for (InstanceInfo instance : eurekaInstances) {
            if (needSync(instance.getMetadata())) {
                log.info("Delete service instance from Eureka, serviceName={}, Ip={}, port={}",
                    instance.getAppName(), instance.getIPAddr(), instance.getPort());
                destNamingService.deregisterInstance(taskDO.getServiceName(),
                    NacosUtils.getGroupNameOrDefault(taskDO.getGroupName()), buildSyncInstance(instance, taskDO));
            }
        }
    }

    private void removeInvalidInstance(TaskDO taskDO, NamingService destNamingService,
        List<InstanceInfo> eurekaInstances, List<Instance> nacosInstances, String serviceName) throws NacosException {
        for (Instance instance : nacosInstances) {
            if (!isExistInEurekaInstance(eurekaInstances, instance) && needDelete(instance.getMetadata(), taskDO)) {
                log.info("Remove invalid service instance from Nacos, serviceName={}, Ip={}, port={}",
                    instance.getServiceName(), instance.getIp(), instance.getPort());
                destNamingService.deregisterInstance(serviceName,
                    NacosUtils.getGroupNameOrDefault(taskDO.getGroupName()), instance.getIp(), instance.getPort());
            }
        }
    }

    private boolean isExistInEurekaInstance(List<InstanceInfo> eurekaInstances, Instance nacosInstance) {

        return eurekaInstances.stream().anyMatch(instance -> instance.getIPAddr().equals(nacosInstance.getIp())
            && instance.getPort() == nacosInstance.getPort());
    }

    private void deleteAllInstance(TaskDO taskDO, NamingService destNamingService, List<Instance> allInstances, String serviceName)
        throws NacosException {
        for (Instance instance : allInstances) {
            if (needDelete(instance.getMetadata(), taskDO)) {
                destNamingService.deregisterInstance(serviceName,
                    NacosUtils.getGroupNameOrDefault(taskDO.getGroupName()), instance);
            }

        }
    }

    private Instance buildSyncInstance(InstanceInfo instance, TaskDO taskDO) {
        Instance temp = new Instance();
        temp.setIp(instance.getIPAddr());
        temp.setPort(instance.getPort());
        temp.setServiceName(instance.getAppName());
        temp.setHealthy(true);

        Map<String, String> metaData = new HashMap<>(instance.getMetadata());
        metaData.put(SkyWalkerConstants.DEST_CLUSTERID_KEY, taskDO.getDestClusterId());
        metaData.put(SkyWalkerConstants.SYNC_SOURCE_KEY,
            skyWalkerCacheServices.getClusterType(taskDO.getSourceClusterId()).getCode());
        metaData.put(SkyWalkerConstants.SOURCE_CLUSTERID_KEY, taskDO.getSourceClusterId());
        temp.setMetadata(metaData);
        return temp;
    }

}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.registry;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.registry.client.event.listener.ServiceInstancesChangedListener;

import java.util.List;

/**
 * NotifyListener. (API, Prototype, ThreadSafe)
 *
 * @see org.apache.dubbo.registry.RegistryService#subscribe(URL, NotifyListener)
 */
public interface NotifyListener {

    /**
     * Triggered when a service change notification is received.
     * 收到服务更改通知时触发
     * <p>
     * Notify needs to support the contract: <br>
     *
     *notify需要支持契约
     *
     * 1. Always notifications on the service interface and the dimension of the data type. that is, won't notify part of the same type data belonging to one service. Users do not need to compare the results of the previous notification.<br>
     *
     * 1.始终通知服务接口和数据类型的维度，也就是说不会通知属于一项服务的同一类型数据的一部分。用户不需要比较之前通知的结果。
     *
     *
     * 2. The first notification at a subscription must be a full notification of all types of data of a service.<br>
     *
     *
     * 2.订阅的第一个通知必须是服务所有累心工属具的完整通知
     *
     * 3. At the time of change, different types of data are allowed to be notified separately, e.g.: providers, consumers, routers, overrides. It allows only one of these types to be notified, but the data of this type must be full, not incremental.<br>
     *
     * 3.变更时，允许分别通知不同类型的数据，列如：提供者、消费者、路由器、覆盖。 他只允许通知其中一种类型，但是这种类型的数据必须是完整 的，而不是增量的
     *
     * 4. If a data type is empty, need to notify a empty protocol with category parameter identification of url data.<br>
     *
     * 4.如果数据类型为空，需要用Url数据的类别参数标记通知空协议
     *
     *
     * 5. The order of notifications to be guaranteed by the notifications(That is, the implementation of the registry). Such as: single thread push, queue serialization, and version comparison.<br>
     * 5.通知要保证的通知顺序（即注册表的执行） 如单线程推送、队列序列化、版本比较
     *
     * @param urls The list of registered information , is always not empty. The meaning is the same as the return value of {@link org.apache.dubbo.registry.RegistryService#lookup(URL)}.
     */
    void notify(List<URL> urls);

    default void addServiceListener(ServiceInstancesChangedListener instanceListener) {
    }

    default ServiceInstancesChangedListener getServiceListener() {
        return null;
    }

    default URL getConsumerUrl() {
        return null;
    }

}

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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.support.wrapper.AbstractCluster;

/**
 * {@link FailoverClusterInvoker}
 *
 */
public class FailoverCluster extends AbstractCluster {

    public final static String NAME = "failover";

    @Override
    public <T> AbstractClusterInvoker<T> doJoin(Directory<T> directory) throws RpcException {

        /**
         * 把directory对象包裹到了 FailoverClusterInvoker中
         * Directory是RegistryDirectory其内部维护了所有服务提供者的invoker列表。 FailoverCluster就是集群容错策略。
         *
         * Dubbo对Cluster扩展接口实现类使用了Wrapper类MockClusterWrapper进行增强 实际上的调用是
         * Cluster$Adaptive---->MockClusterWrapper--->FailbackCluster
         *
         */
        return new FailoverClusterInvoker<>(directory);
    }

}

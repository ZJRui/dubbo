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

import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * AvailableClusterInvoker
 *
 */
public class AvailableClusterInvoker<T> extends AbstractClusterInvoker<T> {

    public AvailableClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    public Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        /**
         * 这里的invokers是 注册中心invoker实例
         * 因为AvailableClusterInvoker对象在创建的时候接受了Directory对象，这个Directory对象内部就包含了 注册中心实例invokers。
         * 因此 当执行 AvailableClusterInvoker的invoke方法的时候 就可以通过 directory得到Invokers。
         * 然后invoke方法内部又调用了 doInvoke，因此doInvoke方法中 能够拿到invokers
         *
         */
        for (Invoker<T> invoker : invokers) {
            if (invoker.isAvailable()) {//判断 特定注册中心是否包含provider服务。
                return invokeWithContext(invoker, invocation);
            }
        }
        throw new RpcException("No provider available in " + invokers);
    }

}

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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcStatus;

import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.rpc.Constants.ACTIVES_KEY;

/**
 * ActiveLimitFilter restrict the concurrent client invocation for a service or service's method from client side.
 * To use active limit filter, configured url with <b>actives</b> and provide valid >0 integer value.
 * <pre>
 *     e.g. <dubbo:reference id="demoService" check="false" interface="org.apache.dubbo.demo.DemoService" "actives"="2"/>
 *      In the above example maximum 2 concurrent invocation is allowed.
 *      If there are more than configured (in this example 2) is trying to invoke remote method, then rest of invocation
 *      will wait for configured timeout(default is 0 second) before invocation gets kill by dubbo.
 * </pre>
 *
 * @see Filter
 */
@Activate(group = CONSUMER, value = ACTIVES_KEY)
public class ActiveLimitFilter implements Filter, Filter.Listener {
    /**
     * 用来控制消费端最大并发调用量
     */

    private static final String ACTIVE_LIMIT_FILTER_START_TIME = "active_limit_filter_start_time";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        /**
         * 服务消费端，进行并发控制的是ProtocolFilterWrapper类中创建的Filter链中的ActiveLimitFilter
         *
         * 获取url和调用的方法名称
         */
        URL url = invoker.getUrl();
        String methodName = invocation.getMethodName();
        /**
         * 获取设置的actives的值，默认为0，和最大可用并发数
         */
        int max = invoker.getUrl().getMethodParameter(methodName, ACTIVES_KEY, 0);
        /**
         * 根据url和方法名获取对应的状态对象
         */
        final RpcStatus rpcStatus = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName());
        /**
         * 判断是否是超过并发限制
         */
        if (!RpcStatus.beginCount(url, methodName, max)) {
            long timeout = invoker.getUrl().getMethodParameter(invocation.getMethodName(), TIMEOUT_KEY, 0);
            long start = System.currentTimeMillis();
            long remain = timeout;
            /**
             * 超过并发限制则阻塞当前线程timeout时间
             */
            synchronized (rpcStatus) {
                while (!RpcStatus.beginCount(url, methodName, max)) {
                    try {
                        rpcStatus.wait(remain);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                    long elapsed = System.currentTimeMillis() - start;
                    remain = timeout - elapsed;
                    /**
                     * 超时还没唤醒则抛出异常
                     */
                    if (remain <= 0) {
                        throw new RpcException(RpcException.LIMIT_EXCEEDED_EXCEPTION,
                                "Waiting concurrent invoke timeout in client-side for service:  " +
                                        invoker.getInterface().getName() + ", method: " + invocation.getMethodName() +
                                        ", elapsed: " + elapsed + ", timeout: " + timeout + ". concurrent invokes: " +
                                        rpcStatus.getActive() + ". max concurrent invoke limit: " + max);
                    }
                }
            }
        }

        /**
         * 到这里说明激活并发数没达到限制，则继续Filter链的调用。正常发起远程调用
         *
         */
        invocation.put(ACTIVE_LIMIT_FILTER_START_TIME, System.currentTimeMillis());

        return invoker.invoke(invocation);
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        String methodName = invocation.getMethodName();
        URL url = invoker.getUrl();
        int max = invoker.getUrl().getMethodParameter(methodName, ACTIVES_KEY, 0);

        /**
         * 远程调用完成后 ，当前激活并发数减去1，并通过notifyAll方法激活所有挂起线程。
         */
        RpcStatus.endCount(url, methodName, getElapsed(invocation), true);
        notifyFinish(RpcStatus.getStatus(url, methodName), max);
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        String methodName = invocation.getMethodName();
        URL url = invoker.getUrl();
        int max = invoker.getUrl().getMethodParameter(methodName, ACTIVES_KEY, 0);

        if (t instanceof RpcException) {
            RpcException rpcException = (RpcException) t;
            if (rpcException.isLimitExceed()) {
                return;
            }
        }
        RpcStatus.endCount(url, methodName, getElapsed(invocation), false);
        notifyFinish(RpcStatus.getStatus(url, methodName), max);
    }

    private long getElapsed(Invocation invocation) {
        Object beginTime = invocation.get(ACTIVE_LIMIT_FILTER_START_TIME);
        return beginTime != null ? System.currentTimeMillis() - (Long) beginTime : 0;
    }

    private void notifyFinish(final RpcStatus rpcStatus, int max) {
        if (max > 0) {
            synchronized (rpcStatus) {
                rpcStatus.notifyAll();
            }
        }
    }
}

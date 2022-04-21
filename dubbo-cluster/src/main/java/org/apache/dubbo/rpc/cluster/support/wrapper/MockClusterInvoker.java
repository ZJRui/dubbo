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
package org.apache.dubbo.rpc.cluster.support.wrapper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.ClusterInvoker;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.support.MockInvoker;

import java.util.List;

import static org.apache.dubbo.rpc.Constants.MOCK_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.FORCE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.INVOCATION_NEED_MOCK;

public class MockClusterInvoker<T> implements ClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(MockClusterInvoker.class);

    private final Directory<T> directory;

    private final Invoker<T> invoker;

    public MockClusterInvoker(Directory<T> directory, Invoker<T> invoker) {
        this.directory = directory;
        this.invoker = invoker;
    }

    @Override
    public URL getUrl() {
        return directory.getConsumerUrl();
    }

    @Override
    public URL getRegistryUrl() {
        return directory.getUrl();
    }

    @Override
    public Directory<T> getDirectory() {
        return directory;
    }

    @Override
    public boolean isDestroyed() {
        return directory.isDestroyed();
    }

    @Override
    public boolean isAvailable() {
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        this.invoker.destroy();
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        Result result;
        /**
         * 查看Url里面是否有mock字段
         */
        String value = getUrl().getMethodParameter(invocation.getMethodName(), MOCK_KEY, Boolean.FALSE.toString()).trim();
        /**
         * 如果没有，或者值默认为false，则说明没有设置降级策略
         */
        if (ConfigUtils.isEmpty(value)) {
            //no mock
            /**
             * 没有mock 正常发起远程调用
             */
            result = this.invoker.invoke(invocation);
        } else if (value.startsWith(FORCE_KEY)) {//设置了force降级策略
            if (logger.isWarnEnabled()) {
                logger.warn("force-mock: " + invocation.getMethodName() + " force-mock enabled , url : " + getUrl());
            }
            //force:direct mock
            /**
             * 如果url中有mock字段并且值异force开头，则说明设置了force:return 降级策略，那么会鸡子儿调用doMockInvoke方法（其内部会调用
             * 创建的MockInvoker的invoke方法） 返回mock值，而不发起远程调用
             *
             */
            result = doMockInvoke(invocation, null);
        } else {
            //fail-mock
            try {
                /**
                 * 如果url里面含有mock字段，并且其值以fail开头，则说明设置了fail:return 降级策略，那么先发起
                 * 远程调用。如果远程调用成功，则直接返回远程返回的结果（如果这里使用了默认的集群容错策略，则会调用FailOverClusterInvoker的invoke方法发起远程
                 * 调用）； 如果远程调用失败，则直接返回mock的值
                 */
                result = this.invoker.invoke(invocation);

                //fix:#4585
                if (result.getException() != null && result.getException() instanceof RpcException) {
                    RpcException rpcException = (RpcException) result.getException();
                    if (rpcException.isBiz()) {
                        throw rpcException;
                    } else {
                        result = doMockInvoke(invocation, rpcException);
                    }
                }

            } catch (RpcException e) {
                if (e.isBiz()) {
                    throw e;
                }

                if (logger.isWarnEnabled()) {
                    logger.warn("fail-mock: " + invocation.getMethodName() + " fail-mock enabled , url : " + getUrl(), e);
                }
                result = doMockInvoke(invocation, e);
            }
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Result doMockInvoke(Invocation invocation, RpcException e) {
        Result result;
        Invoker<T> mockInvoker;

        List<Invoker<T>> mockInvokers = selectMockInvoker(invocation);
        if (CollectionUtils.isEmpty(mockInvokers)) {
            mockInvoker = (Invoker<T>) new MockInvoker(getUrl(), directory.getInterface());
        } else {
            mockInvoker = mockInvokers.get(0);
        }
        try {
            result = mockInvoker.invoke(invocation);
        } catch (RpcException mockException) {
            if (mockException.isBiz()) {
                result = AsyncRpcResult.newDefaultAsyncResult(mockException.getCause(), invocation);
            } else {
                throw new RpcException(mockException.getCode(), getMockExceptionMessage(e, mockException), mockException.getCause());
            }
        } catch (Throwable me) {
            throw new RpcException(getMockExceptionMessage(e, me), me.getCause());
        }
        return result;
    }

    private String getMockExceptionMessage(Throwable t, Throwable mt) {
        String msg = "mock error : " + mt.getMessage();
        if (t != null) {
            msg = msg + ", invoke error is :" + StringUtils.toString(t);
        }
        return msg;
    }

    /**
     * Return MockInvoker
     * Contract：
     * directory.list() will return a list of normal invokers if Constants.INVOCATION_NEED_MOCK is absent or not true in invocation, otherwise, a list of mock invokers will return.
     * if directory.list() returns more than one mock invoker, only one of them will be used.
     *
     * @param invocation
     * @return
     */
    private List<Invoker<T>> selectMockInvoker(Invocation invocation) {
        List<Invoker<T>> invokers = null;
        //TODO generic invoker？
        if (invocation instanceof RpcInvocation) {
            //Note the implicit contract (although the description is added to the interface declaration, but extensibility is a problem. The practice placed in the attachment needs to be improved)
            invocation.setAttachment(INVOCATION_NEED_MOCK, Boolean.TRUE.toString());
            //directory will return a list of normal invokers if Constants.INVOCATION_NEED_MOCK is absent or not true in invocation, otherwise, a list of mock invokers will return.
            try {
                RpcContext.getServiceContext().setConsumerUrl(getUrl());
                invokers = directory.list(invocation);
            } catch (RpcException e) {
                if (logger.isInfoEnabled()) {
                    logger.info("Exception when try to invoke mock. Get mock invokers error for service:"
                            + getUrl().getServiceInterface() + ", method:" + invocation.getMethodName()
                            + ", will construct a new mock with 'new MockInvoker()'.", e);
                }
            }
        }
        return invokers;
    }

    @Override
    public String toString() {
        return "invoker :" + this.invoker + ",directory: " + this.directory;
    }
}

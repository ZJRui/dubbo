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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.utils.AtomicPositiveInteger;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.FutureContext;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.TimeoutCountDown;
import org.apache.dubbo.rpc.protocol.AbstractInvoker;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_VERSION;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLE_TIMEOUT_COUNTDOWN_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_ATTACHMENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIME_COUNTDOWN_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;

/**
 * DubboInvoker
 */
public class DubboInvoker<T> extends AbstractInvoker<T> {

    private final ExchangeClient[] clients;

    private final AtomicPositiveInteger index = new AtomicPositiveInteger();

    private final String version;

    private final ReentrantLock destroyLock = new ReentrantLock();

    private final Set<Invoker<?>> invokers;

    private final int serverShutdownTimeout;

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients) {
        this(serviceType, url, clients, null);
    }

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients, Set<Invoker<?>> invokers) {
        super(serviceType, url, new String[]{INTERFACE_KEY, GROUP_KEY, TOKEN_KEY});
        this.clients = clients;
        // get version.
        this.version = url.getVersion(DEFAULT_VERSION);
        this.invokers = invokers;
        this.serverShutdownTimeout = ConfigurationUtils.getServerShutdownTimeout(getUrl().getScopeModel());
    }

    @Override
    protected Result doInvoke(final Invocation invocation) throws Throwable {
        /**
         * 设置附加属性
         */
        RpcInvocation inv = (RpcInvocation) invocation;
        final String methodName = RpcUtils.getMethodName(invocation);
        inv.setAttachment(PATH_KEY, getUrl().getPath());
        inv.setAttachment(VERSION_KEY, version);

        /**
         * 获取远程调用client
         */
        ExchangeClient currentClient;
        if (clients.length == 1) {
            currentClient = clients[0];
        } else {
            currentClient = clients[index.getAndIncrement() % clients.length];
        }
        try {
            /**
             * 是否为oneWay 也就是不需要响应结果的请求
             */
            boolean isOneway = RpcUtils.isOneway(getUrl(), invocation);
            int timeout = calculateTimeout(invocation, methodName);
            invocation.setAttachment(TIMEOUT_KEY, timeout);
            if (isOneway) {
                boolean isSent = getUrl().getMethodParameter(methodName, Constants.SENT_KEY, false);
                currentClient.send(inv, isSent);
                return AsyncRpcResult.newDefaultAsyncResult(invocation);
            } else {
                ExecutorService executor = getCallbackExecutor(getUrl(), inv);
                /**
                 *
                 * 在DubboInovker中 使用如下内容发起请求
                 *  CompletableFuture<AppResponse> appResponseFuture =
                 *                         currentClient.request(inv, timeout, executor).thenApply(obj -> (AppResponse) obj);
                 *
                 * 异步请求，该调用不会阻塞，马上返回一个Future对象， 框孔二狗IP设在建瓯打牌 RpcContext中
                 * 这里的currentClient
                 *
                 * DubboInvoker.invoke-->dubboInvoer.doInvoker-->ReferenceCountExchangeClinet.request -->HeaderExchangeClient.request
                 * -->HeaderExchagneChannel
                 *
                 *当服务消费端业务线程发起请求后，在 HeaderExchangeChannel的request中年创建一个DefaultFuture对象,本质上是一个CompletableFuture，
                 * 并设置到RpcContext中，然后在启动 IO线程发起请求后调用线程就返回了null结果； 当业务线程从RpcContext获取future对象并调用其get方法获取真实的响应结果后，
                 * 当前线程会被阻塞。 当服务提供端把结果写会调用方法后，调用方线程模型中线程池里的线程会把结果写入DefaultFuture对象内的结果变量中。
                 * 接着CompletableFuture的complete方法会unpark 激活被get阻塞的业务线程，业务线程从get方法返回结果响应。
                 *
                 * 这种实现异步调用的方式基于 从返回的future调用get方法，缺点是当业务线程调用get时会被阻塞， Dubbo提供了在future对象上设置
                 * 回调函数的方式，让我们实现真正的异步调用。这种方式下 调用方将结果写入Future，然后对回调函数进行回调。这个过程不需要业务线程干预，实现了真正的异步调用。
                 *
                 * CompletableFuture<String>  future=RpcContext.getContext.getCompletableFuture();
                 * future.whenComplete((v,t)->{
                 *     if (null!=t){
                 *       t.printStackTrace()
                 *     }elese{
                 *         sout(v)
                 *     }
                 * })
                 *
                 *
                 */
                CompletableFuture<AppResponse> appResponseFuture =
                        currentClient.request(inv, timeout, executor).thenApply(obj -> (AppResponse) obj);
                // save for 2.6.x compatibility, for example, TraceFilter in Zipkin uses com.alibaba.xxx.FutureAdapter
                FutureContext.getContext().setCompatibleFuture(appResponseFuture);
                AsyncRpcResult result = new AsyncRpcResult(appResponseFuture, inv);
                result.setExecutor(executor);
                return result;
            }
        } catch (TimeoutException e) {
            throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        } catch (RemotingException e) {
            throw new RpcException(RpcException.NETWORK_EXCEPTION, "Failed to invoke remote method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        if (!super.isAvailable()) {
            return false;
        }
        for (ExchangeClient client : clients) {
            if (client.isConnected() && !client.hasAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY)) {
                //cannot write == not Available ?
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        destroyInternal(false);
    }

    @Override
    public void destroyAll() {
        destroyInternal(true);
    }

    /**
     * when destroy unused invoker, closeAll should be true
     *
     * @param closeAll
     */
    private void destroyInternal(boolean closeAll) {
        // in order to avoid closing a client multiple times, a counter is used in case of connection per jvm, every
        // time when client.close() is called, counter counts down once, and when counter reaches zero, client will be
        // closed.
        if (!super.isDestroyed()) {
            // double check to avoid dup close
            destroyLock.lock();
            try {
                if (super.isDestroyed()) {
                    return;
                }
                super.destroy();
                if (invokers != null) {
                    invokers.remove(this);
                }
                for (ExchangeClient client : clients) {
                    try {
                        if (closeAll) {
                            client.closeAll(serverShutdownTimeout);
                        } else {
                            client.close(serverShutdownTimeout);
                        }
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }

            } finally {
                destroyLock.unlock();
            }
        }
    }

    private int calculateTimeout(Invocation invocation, String methodName) {
        Object countdown = RpcContext.getClientAttachment().getObjectAttachment(TIME_COUNTDOWN_KEY);
        int timeout;
        if (countdown == null) {
            timeout = (int) RpcUtils.getTimeout(getUrl(), methodName, RpcContext.getClientAttachment(), DEFAULT_TIMEOUT);
            if (getUrl().getParameter(ENABLE_TIMEOUT_COUNTDOWN_KEY, false)) {
                invocation.setObjectAttachment(TIMEOUT_ATTACHMENT_KEY, timeout); // pass timeout to remote server
            }
        } else {
            TimeoutCountDown timeoutCountDown = (TimeoutCountDown) countdown;
            timeout = (int) timeoutCountDown.timeRemaining(TimeUnit.MILLISECONDS);
            invocation.setObjectAttachment(TIMEOUT_ATTACHMENT_KEY, timeout);// pass timeout to remote server
        }
        return timeout;
    }
}

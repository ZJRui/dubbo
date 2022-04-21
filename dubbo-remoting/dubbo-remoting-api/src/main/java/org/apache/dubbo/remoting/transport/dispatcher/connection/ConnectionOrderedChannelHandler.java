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
package org.apache.dubbo.remoting.transport.dispatcher.connection;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.threadpool.support.AbortPolicyWithReport;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.ExecutionException;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.ChannelState;
import org.apache.dubbo.remoting.transport.dispatcher.WrappedChannelHandler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_THREAD_NAME;
import static org.apache.dubbo.common.constants.CommonConstants.THREAD_NAME_KEY;
import static org.apache.dubbo.remoting.Constants.CONNECT_QUEUE_CAPACITY;
import static org.apache.dubbo.remoting.Constants.CONNECT_QUEUE_WARNING_SIZE;
import static org.apache.dubbo.remoting.Constants.DEFAULT_CONNECT_QUEUE_WARNING_SIZE;

public class ConnectionOrderedChannelHandler extends WrappedChannelHandler {

    protected final ThreadPoolExecutor connectionExecutor;
    private final int queueWarningLimit;

    public ConnectionOrderedChannelHandler(ChannelHandler handler, URL url) {
        super(handler, url);

        /**
         *
         * Dubbo默认使用Netty，服务提供方NettyServer使用两极线程池，其中EventLoopGroup（boss）主要用来接收客户端的链接请求，并
         * 把完成TCP三次握手的链接分发给EventLoopGroup（worker）来处理。 我们把boss和worker线程组称为Io线程。
         * 如果服务提供的逻辑处理能够迅速挖完成，并且不会发起新的io请求，那么直接在io线程上处理会更快，因为这样减少了线程池调度和上下文切换。
         * 根据请求的消息类是被io线程处理还是被业务线程池处理，dubbo提供几种线程模型：
         * all:(AllDispatcher 类)：所有消息都被派发到业务线程池，这些消息包括请求、响应、连接事件、断开事件、心跳事件等
         * direct(DirectDispatcher类)所有消息都不派发到业务线程池，全部都在io县城上直接执行。
         * message(MessageOnlyDispatcher):只有请求响应消息派发到业务线程池，其他消息如连接事件、断开事件、心跳事件 直接在io线程上执行
         * execution(ExecutionDispathcer类)：只把请求类消息派发到业务线程池处理，但是响应、连接事件、断开事件、心跳事件等消息直接在io线程上执行。
         * connection(ConnectionOrderdDispathcer):在io线程上将连接事件、断开事件放入队列，有序地逐个执行，其他消息派发到业务线程池处理。
         * all模型是默认的线程模型。
         *
         *
         * 创建线程池
         */
        String threadName = url.getParameter(THREAD_NAME_KEY, DEFAULT_THREAD_NAME);
        /**
         *
         * 创建只有一个线程的线程池 和一个有限元素的队列。 这个线程池用来实现把连接建立和断开事件顺序化处理
         */
        connectionExecutor = new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(url.getPositiveParameter(CONNECT_QUEUE_CAPACITY, Integer.MAX_VALUE)),
                new NamedThreadFactory(threadName, true),
                new AbortPolicyWithReport(threadName, url)
        );  // FIXME There's no place to release connectionExecutor!
        /**
         * 线程池队列元素限制警告
         *
         */
        queueWarningLimit = url.getParameter(CONNECT_QUEUE_WARNING_SIZE, DEFAULT_CONNECT_QUEUE_WARNING_SIZE);
    }

    @Override
    public void connected(Channel channel) throws RemotingException {
        try {
            checkQueueLength();
            connectionExecutor.execute(new ChannelEventRunnable(channel, handler, ChannelState.CONNECTED));
        } catch (Throwable t) {
            throw new ExecutionException("connect event", channel, getClass() + " error when process connected event .", t);
        }
    }

    @Override
    public void disconnected(Channel channel) throws RemotingException {
        try {
            checkQueueLength();
            connectionExecutor.execute(new ChannelEventRunnable(channel, handler, ChannelState.DISCONNECTED));
        } catch (Throwable t) {
            throw new ExecutionException("disconnected event", channel, getClass() + " error when process disconnected event .", t);
        }
    }

    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        ExecutorService executor = getPreferredExecutorService(message);
        try {
            /**
             * 请求、响应事件交给dubbo线程池执行
             *
             */
            executor.execute(new ChannelEventRunnable(channel, handler, ChannelState.RECEIVED, message));
        } catch (Throwable t) {
            if (message instanceof Request && t instanceof RejectedExecutionException) {
                sendFeedback(channel, (Request) message, t);
                return;
            }
            throw new ExecutionException(message, channel, getClass() + " error when process received event .", t);
        }
    }

    @Override
    public void caught(Channel channel, Throwable exception) throws RemotingException {
        ExecutorService executor = getSharedExecutorService();
        try {
            executor.execute(new ChannelEventRunnable(channel, handler, ChannelState.CAUGHT, exception));
        } catch (Throwable t) {
            throw new ExecutionException("caught event", channel, getClass() + " error when process caught event .", t);
        }
    }

    private void checkQueueLength() {
        if (connectionExecutor.getQueue().size() > queueWarningLimit) {
            logger.warn(new IllegalThreadStateException("connectionordered channel handler queue size: " + connectionExecutor.getQueue().size() + " exceed the warning limit number :" + queueWarningLimit));
        }
    }
}

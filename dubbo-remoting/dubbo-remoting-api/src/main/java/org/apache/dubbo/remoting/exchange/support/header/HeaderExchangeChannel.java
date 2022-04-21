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
package org.apache.dubbo.remoting.exchange.support.header;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * ExchangeReceiver
 */
final class HeaderExchangeChannel implements ExchangeChannel {

    private static final Logger logger = LoggerFactory.getLogger(HeaderExchangeChannel.class);

    private static final String CHANNEL_KEY = HeaderExchangeChannel.class.getName() + ".CHANNEL";

    private final Channel channel;

    private volatile boolean closed = false;

    HeaderExchangeChannel(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel == null");
        }
        this.channel = channel;
    }

    static HeaderExchangeChannel getOrAddChannel(Channel ch) {
        if (ch == null) {
            return null;
        }
        HeaderExchangeChannel ret = (HeaderExchangeChannel) ch.getAttribute(CHANNEL_KEY);
        if (ret == null) {
            ret = new HeaderExchangeChannel(ch);
            if (ch.isConnected()) {
                ch.setAttribute(CHANNEL_KEY, ret);
            }
        }
        return ret;
    }

    static void removeChannelIfDisconnected(Channel ch) {
        if (ch != null && !ch.isConnected()) {
            ch.removeAttribute(CHANNEL_KEY);
        }
    }

    static void removeChannel(Channel ch) {
        if (ch != null) {
            ch.removeAttribute(CHANNEL_KEY);
        }
    }

    @Override
    public void send(Object message) throws RemotingException {
        send(message, false);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message + ", cause: The channel " + this + " is closed!");
        }
        if (message instanceof Request
                || message instanceof Response
                || message instanceof String) {
            channel.send(message, sent);
        } else {
            Request request = new Request();
            request.setVersion(Version.getProtocolVersion());
            request.setTwoWay(false);
            request.setData(message);
            channel.send(request, sent);
        }
    }

    @Override
    public CompletableFuture<Object> request(Object request) throws RemotingException {
        return request(request, null);
    }

    @Override
    public CompletableFuture<Object> request(Object request, int timeout) throws RemotingException {
        return request(request, timeout, null);
    }

    @Override
    public CompletableFuture<Object> request(Object request, ExecutorService executor) throws RemotingException {
        return request(request, channel.getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT), executor);
    }

    @Override
    public CompletableFuture<Object> request(Object request, int timeout, ExecutorService executor) throws RemotingException {
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send request " + request + ", cause: The channel " + this + " is closed!");
        }
        // create request.
        Request req = new Request();
        /**
         *
         */
        req.setVersion(Version.getProtocolVersion());
        req.setTwoWay(true);
        req.setData(request);
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
        DefaultFuture future = DefaultFuture.newFuture(channel, req, timeout, executor);
        try {
            /**
             * 使用底层通信异步发送请求，使用Netty的io线程把请求写入远端。
             *
             * 问题：使用netty的channel写出数据的时候显然是在业务线程内调用的
             * 那么 这个写数据的操作 是业务线程还是Netty的Io线程？
             *
             * 这个channel.send 会马上返回，不会阻塞用户线程
             */
            channel.send(req);
        } catch (RemotingException e) {
            future.cancel();
            throw e;
        }
        return future;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            // graceful close
            DefaultFuture.closeChannel(channel);
            channel.close();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    // graceful close
    @Override
    public void close(int timeout) {
        if (closed) {
            return;
        }
        if (timeout > 0) {
            long start = System.currentTimeMillis();
            while (DefaultFuture.hasFuture(channel)
                    && System.currentTimeMillis() - start < timeout) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        close();
    }

    @Override
    public void startClose() {
        channel.startClose();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return channel.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return channel.getRemoteAddress();
    }

    @Override
    public URL getUrl() {
        return channel.getUrl();
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return channel.getChannelHandler();
    }

    @Override
    public ExchangeHandler getExchangeHandler() {
        return (ExchangeHandler) channel.getChannelHandler();
    }

    @Override
    public Object getAttribute(String key) {
        return channel.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        channel.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        channel.removeAttribute(key);
    }

    @Override
    public boolean hasAttribute(String key) {
        return channel.hasAttribute(key);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((channel == null) ? 0 : channel.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        HeaderExchangeChannel other = (HeaderExchangeChannel) obj;
        if (channel == null) {
            if (other.channel != null) {
                return false;
            }
        } else if (!channel.equals(other.channel)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return channel.toString();
    }

}

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
package org.apache.dubbo.remoting.transport.dispatcher.all;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Dispatcher;

/**
 * default thread pool configure
 */
public class AllDispatcher implements Dispatcher {

    public static final String NAME = "all";

    /**
     *
     *
     * Dubbo默认使用Netty，服务提供方NettyServer使用两极线程池，其中EventLoopGroup（boss）主要用来接收客户端的链接请求，并
     * 把完成TCP三次握手的链接分发给EventLoopGroup（worker）来处理。 我们把boss和worker线程组称为Io线程。
     *
     * 如果服务提供的逻辑处理能够迅速挖完成，并且不会发起新的io请求，那么直接在io线程上处理会更快，因为这样减少了线程池调度和上下文切换。
     *
     * 根据请求的消息类是被io线程处理还是被业务线程池处理，dubbo提供几种线程模型：
     *
     * all:(AllDispatcher 类)：所有消息都被派发到业务线程池，这些消息包括请求、响应、连接事件、断开事件、心跳事件等
     *
     * direct(DirectDispatcher类)所有消息都不派发到业务线程池，全部都在io县城上直接执行。
     * message(MessageOnlyDispatcher):只有请求响应消息派发到业务线程池，其他消息如连接事件、断开事件、心跳事件 直接在io线程上执行
     *
     * execution(ExecutionDispathcer类)：只把请求类消息派发到业务线程池处理，但是响应、连接事件、断开事件、心跳事件等消息直接在io线程上执行。
     * connection(ConnectionOrderdDispathcer):在io线程上将连接事件、断开事件放入队列，有序地逐个执行，其他消息派发到业务线程池处理。
     *
     * all模型是默认的线程模型。
     *
     * 问题： Dubbo使用netty的channelHandler 对于接收到的数据是如何区分 该数据是请求  还是响应？
     * Dubbo的NettyHandler类实现了Netty的SimpleChannelHandler ，作为Netty的channelHandler接口实现类。
     * 当有数据到来（不管该数据是 客户端的请求 还是其他服务器返回的响应）都会执行Netty的channelHandler的messageReceived
     * 方法。
     *
     * Dubbo自身也提供了ChannelHandler SPI扩展接口， 在NettyHandler的messageReceive方法中会调用Dubbo的
     * ChannelHandler的SPI接口实现类。这个handler可能是DirectChannelHandler 、AllChannelHandler 或者ExecutionChannelHandler。
     *
     * Netty的Pipeline中设置了编解码 ，因此Netty的channelHandler的messageReceived 方法中会将数据解码为对象
     * 之后传递给NettyHandler，ExecutionChannelHandler通过判断对象是Request对象来确定接收到的是请求 还是 响应。
     *
     * @param handler
     * @param url
     * @return
     */
    @Override
    public ChannelHandler dispatch(ChannelHandler handler, URL url) {
        /**
         * 使用AllChannelHandler对 handler进行一次包装。
         * handler中会调用AllChannelHandler的方法，从而将请求交给AllChannelHandler
         *
         * AllChannleHandler 将消息交给线程池处理
         * 注意交给线程池的时候同时将channel对象传递过去了
         */
        return new AllChannelHandler(handler, url);
    }

}

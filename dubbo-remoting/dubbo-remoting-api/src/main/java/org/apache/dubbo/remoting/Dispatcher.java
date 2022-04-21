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
package org.apache.dubbo.remoting;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionScope;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.remoting.transport.dispatcher.all.AllDispatcher;

/**
 * ChannelHandlerWrapper (SPI, Singleton, ThreadSafe)
 */
@SPI(value = AllDispatcher.NAME, scope = ExtensionScope.FRAMEWORK)
public interface Dispatcher {

    /**
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
     *
     * @param handler
     * @param url
     * @return
     */
    @Adaptive({Constants.DISPATCHER_KEY, "dispather", "channel.handler"})
    // The last two parameters are reserved for compatibility with the old configuration
    ChannelHandler dispatch(ChannelHandler handler, URL url);

}

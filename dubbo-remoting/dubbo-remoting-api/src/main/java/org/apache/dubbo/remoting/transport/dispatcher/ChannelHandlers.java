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
package org.apache.dubbo.remoting.transport.dispatcher;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Dispatcher;
import org.apache.dubbo.remoting.exchange.support.header.HeartbeatHandler;
import org.apache.dubbo.remoting.transport.MultiMessageHandler;

public class ChannelHandlers {

    private static ChannelHandlers INSTANCE = new ChannelHandlers();

    protected ChannelHandlers() {
    }

    public static ChannelHandler wrap(ChannelHandler handler, URL url) {
        return ChannelHandlers.getInstance().wrapInternal(handler, url);
    }

    protected static ChannelHandlers getInstance() {
        return INSTANCE;
    }

    static void setTestingChannelHandlers(ChannelHandlers instance) {
        INSTANCE = instance;
    }

    protected ChannelHandler wrapInternal(ChannelHandler handler, URL url) {
        /**
         * 在下面的dispatch函数内会确定消费端Dubbo内部的线程池模型
         *
         * Dubbo 何时确实使用哪种线程模型？
         * 服务提供方会启动NettyServer来监听消费方的连接在 下面的 ChannelHandlers.wrap(handler, url) 中会执行
         * getExtensionLoader(Dispatcher.class)
         *                 .getAdaptiveExtension().dispatch(handler, url)
         * 根据url里的线程模型来选择具体的Dispatcher实现类。  默认是allDispatcher
         *
         */
        return new MultiMessageHandler(new HeartbeatHandler(url.getOrDefaultFrameworkModel().getExtensionLoader(Dispatcher.class)
                .getAdaptiveExtension().dispatch(handler, url)));
    }
}

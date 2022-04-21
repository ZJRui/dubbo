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
package org.apache.dubbo.remoting.transport.netty;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.transport.AbstractServer;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelHandlers;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.apache.dubbo.common.constants.CommonConstants.BACKLOG_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.IO_THREADS_KEY;
import static org.apache.dubbo.remoting.Constants.EVENT_LOOP_BOSS_POOL_NAME;
import static org.apache.dubbo.remoting.Constants.EVENT_LOOP_WORKER_POOL_NAME;

/**
 * NettyServer
 */
public class NettyServer extends AbstractServer implements RemotingServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

    private Map<String, Channel> channels; // <ip:port, channel>

    private ServerBootstrap bootstrap;

    private org.jboss.netty.channel.Channel channel;

    public NettyServer(URL url, ChannelHandler handler) throws RemotingException {
        /**
         * NettyServer 本身是一个ChannelHandler，其构造器中会执行ChannelHandler.wrap方法会导致 通过AllDispatcher
         * 创建一个 AllChannelHandler 。
         *
         * 消费端发起TCP链接并完成后，服务提供方法的NettyServer的connected方法会被激活，该方法的执行是在Netty的IO线程上执行的。
         *
         * 为了可以及时释放IO线程，Netty默认的线程模型为All，所有消息都派发到Dubbo内部的业务线程池，这些消息包括请求事件、响应事件、
         * 连接事件、断开事件、心跳事件，这里对应的是AllChannelHandler类把IO线程接收到的所有消息包装为ChannelEventRunnable任务并投递到
         * 线程池中。
         *
         * =============
         * Dubbo 何时确实使用哪种线程模型？
         * 服务提供方会启动NettyServer来监听消费方的连接在 下面的 ChannelHandlers.wrap(handler, url) 中会执行
         * getExtensionLoader(Dispatcher.class)
         *                 .getAdaptiveExtension().dispatch(handler, url)
         * 根据url里的线程模型来选择具体的Dispatcher实现类。
         *
         */
        super(ExecutorUtil.setThreadName(url, SERVER_THREAD_POOL_NAME), ChannelHandlers.wrap(handler, url));
    }

    @Override
    protected void doOpen() throws Throwable {
        NettyHelper.setNettyLoggerFactory();
        ExecutorService boss = Executors.newCachedThreadPool(new NamedThreadFactory(EVENT_LOOP_BOSS_POOL_NAME, true));
        ExecutorService worker = Executors.newCachedThreadPool(new NamedThreadFactory(EVENT_LOOP_WORKER_POOL_NAME, true));
        ChannelFactory channelFactory = new NioServerSocketChannelFactory(boss, worker, getUrl().getPositiveParameter(IO_THREADS_KEY, Constants.DEFAULT_IO_THREADS));
        bootstrap = new ServerBootstrap(channelFactory);

        /**
         * NettyHandler实现了 Netty的SimpleChannelHandler
         *
         * 注意这里 创建NettyHandler的时候传递了this NettyServer对象， NettyServer对象本质上是一个ChannelHandler
         *
         * NettyServer->AbstractServer->AbstractEndpoint->AbstractPeer->ChannelHandler
         *
         * nettyServer对象本身作为一个ChannelHandler，而且NettyServer对象的构造函数接收channelHandler对象
         *
         * ================
         * 我们知道 DubboProtocol中有一个ChannelHandler，当方法调用请求到来的时候会执行这个requestHandler，这个requestHandler是如何传递到Netty中的？
         *
         * 在DubboProtocol#createServer()方法中requestHandler 被传递到 HeaderExchanger 的bind方法中
         * 在headerExchanger的bind方法中 创建了一个HeaderExchangeHandler 包装requestHandler
         *
         * 然后又创建了一个DecodeHandler 包装 HeaderExchangeHandler
         * 然后这个DecodeHandler被传递到NettyTransporter的bind方法中 在这个bind方法中创建NettyServer
         *
         *
         * nettyServer对象本身作为一个ChannelHandler，而且NettyServer对象的构造函数接收channelHandler对象
         * NettyServer->AbstractServer->AbstractEndpoint->AbstractPeer->ChannelHandler
         *
         *  这样所有的ChannelHandler都汇聚到了Dubbo的NettyServer这个ChannelHandler中
         *
         *在 org.apache.dubbo.remoting.transport.netty.NettyServer#doOpen()方法中 会启动Netty。同时
         * 在doOpen方法中 又创建了一个 NettyHandler 对象，这个NettyHandler对象也是一个ChannelHandler，同时将nettyServer 传递给
         * 这个NettyHandler。 最终这个nettyHandler被添加到 NettyPipeline中。
         *
         *  这就是requestHandler 的调用逻辑
         *
         *
         */
        final NettyHandler nettyHandler = new NettyHandler(getUrl(), this);
        channels = nettyHandler.getChannels();
        // https://issues.jboss.org/browse/NETTY-365
        // https://issues.jboss.org/browse/NETTY-379
        // final Timer timer = new HashedWheelTimer(new NamedThreadFactory("NettyIdleTimer", true));
        bootstrap.setOption("child.tcpNoDelay", true);
        bootstrap.setOption("backlog", getUrl().getPositiveParameter(BACKLOG_KEY, Constants.DEFAULT_BACKLOG));
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() {
                NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyServer.this);
                ChannelPipeline pipeline = Channels.pipeline();
                /*int idleTimeout = getIdleTimeout();
                if (idleTimeout > 10000) {
                    pipeline.addLast("timer", new IdleStateHandler(timer, idleTimeout / 1000, 0, 0));
                }*/
                pipeline.addLast("decoder", adapter.getDecoder());
                pipeline.addLast("encoder", adapter.getEncoder());
                /**
                 * 将 Dubbo 实现的NettyHandler添加到 Pipeline
                 *
                 *
                 *
                 */
                pipeline.addLast("handler", nettyHandler);
                return pipeline;
            }
        });
        // bind
        channel = bootstrap.bind(getBindAddress());
    }

    @Override
    protected void doClose() throws Throwable {
        try {
            if (channel != null) {
                // unbind.
                channel.close();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            Collection<org.apache.dubbo.remoting.Channel> channels = getChannels();
            if (CollectionUtils.isNotEmpty(channels)) {
                for (org.apache.dubbo.remoting.Channel channel : channels) {
                    try {
                        channel.close();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (bootstrap != null) {
                // release external resource.
                bootstrap.releaseExternalResources();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (channels != null) {
                channels.clear();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public Collection<Channel> getChannels() {
        Collection<Channel> chs = new HashSet<Channel>();
        for (Channel channel : this.channels.values()) {
            if (channel.isConnected()) {
                chs.add(channel);
            } else {
                channels.remove(NetUtils.toAddressString(channel.getRemoteAddress()));
            }
        }
        return chs;
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        return channels.get(NetUtils.toAddressString(remoteAddress));
    }

    @Override
    public boolean isBound() {
        return channel.isBound();
    }

}

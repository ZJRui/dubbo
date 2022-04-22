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
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.support.SerializableClassRegistry;
import org.apache.dubbo.common.serialize.support.SerializationOptimizer;
import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchangers;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LAZY_CONNECT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.STUB_EVENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.remoting.Constants.CHANNEL_READONLYEVENT_SENT_KEY;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;
import static org.apache.dubbo.remoting.Constants.CODEC_KEY;
import static org.apache.dubbo.remoting.Constants.CONNECTIONS_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_HEARTBEAT;
import static org.apache.dubbo.remoting.Constants.DEFAULT_REMOTING_CLIENT;
import static org.apache.dubbo.remoting.Constants.HEARTBEAT_KEY;
import static org.apache.dubbo.remoting.Constants.SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_REMOTING_SERVER;
import static org.apache.dubbo.rpc.Constants.DEFAULT_STUB_EVENT;
import static org.apache.dubbo.rpc.Constants.IS_SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.STUB_EVENT_METHODS_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.CALLBACK_SERVICE_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DEFAULT_SHARE_CONNECTIONS;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.IS_CALLBACK_SERVICE;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_CONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_DISCONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.OPTIMIZER_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.SHARE_CONNECTIONS_KEY;


/**
 * dubbo protocol support.
 */
public class DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    public static final int DEFAULT_PORT = 20880;
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";

    /**
     * <host:port,Exchanger>
     * {@link Map<String, List<ReferenceCountExchangeClient>}
     */
    private final Map<String, Object> referenceClientMap = new ConcurrentHashMap<>();
    private static final Object PENDING_OBJECT = new Object();
    private final Set<String> optimizers = new ConcurrentHashSet<>();

    private AtomicBoolean destroyed = new AtomicBoolean();

    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {


        /**
         *
         * @param channel
         * @param message
         * @return
         * @throws RemotingException
         */
        @Override
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message) throws RemotingException {

            /**
             * DubboProtocol中的RequestHandler对象 实现了ExchangeHandler 接口， exchangeHandler接口继承自ChannelHandler 接口。
             * RequestHandler对象被 HeaderExchangeHandler对象包装。
             * 在HeaderExchangeHandler#received(org.apache.dubbo.remoting.Channel, java.lang.Object)
             * 方法中会调用requestHandler的received方法. 这个received 方法是ChannelHandler的。
             * RequestHandler的 received方法中又调用了reply方法
             */
            if (!(message instanceof Invocation)) {
                throw new RemotingException(channel, "Unsupported request: "
                        + (message == null ? null : (message.getClass().getName() + ": " + message))
                        + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
            }

            Invocation inv = (Invocation) message;
            /*
             *  那么问题 就是 Invoker对象的invoke方法 何时被调用？ 也就是什么时候在哪里收到  触发Invoker的invoke执行
             *  从而执行wrapper的invokeMethod，从而执行服务提供者的方法？
             *
             *  服务发布的时候虽然得到了Invoker对象，还需要使用Protocol对象对外发布
             *  Exporter<?> exporter = protocolSPI.export(invoker);--->protocolSPI 其实是Protocol$Adaptive 最终会执行DubboProtocol的export
             *  对于DubboProtocol而言，在其export方法中会创建一个DubboExporter对象，这个DubboExporter对象中持有Invoker对象。
             *  同时DubboProtocol的export在创建Export对象的时候 会将DubboProtocol对象的一个Map<String, Exporter<?>> exporterMap
             *  属性传递给DubboExporter对象，然后DubboExporter对象将自身放置到这个Map中。 这个map的key是serviceKey。
             *  export的时候还会创建一个nettyServer.
             *  DubboProtocol对象中有一个ExchangeHandler requestHandler， DubboProtocol的exptor方法会针对不同的服务只开启一个nettyServer。
             *  在创建NettyServer的时候会 传递 requestHandler给NettyServer，因此当消息来临的时候就会执行 requestHandler的方法
             *
             *  当服务提供者端收到消息请求的时候会执行org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol#requestHandler 的reply方法。
             *  requestHandler首先从请求中取出serviceKey，然后在DubboProtocol对象的Map中根据serviceKey找到 DubboExporter对象，
             *  然后根据export对象找到Invoker对象，然后读取请求中要执行的方法名称和参数等信息封装成Invocation。最终执行Invoker的invoke方法。
             *
             *===================
              我们知道 在org.apache.dubbo.config.ServiceConfig#doExportUrl(org.apache.dubbo.common.URL, boolean)
             * Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
             * 得到的Invoker是 JavassistProxyFactory中创建的一个 new AbstractProxyInvoker
             *
             *   然后在 serviceconfig的doExportUrl中执行
             *   Exporter<?> exporter = protocolSPI.export(invoker);
             *   这里的protocolSPI是RegistryProtocol，RegistryProtocol的export方法中 会执行RegistryProtocol的doLocalExport
             *   在执行doLocalExport之前对 originInvoker(new  AbstractProxyInvoker)进行了一次 包装InvokerDelegate，
             *   然后使用protocol（DubboProtocol）进行export.  因此 我们说 DubboProtocol export方法中接收的实际上是
             * RegistryProtocol中的InvokerDelegate。   InvokerDelegate持有 new AbstractProxyInvoker。
             * InvokeDelegate的invoke方法会调用new AbstractProxyInvoker的invoke
             *
             *
             */
            Invoker<?> invoker = getInvoker(channel, inv);
            inv.setServiceModel(invoker.getUrl().getServiceModel());
            // switch TCCL
            if (invoker.getUrl().getServiceModel() != null) {
                Thread.currentThread().setContextClassLoader(invoker.getUrl().getServiceModel().getClassLoader());
            }
            // need to consider backward-compatibility if it's a callback
            if (Boolean.TRUE.toString().equals(inv.getObjectAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                String methodsStr = invoker.getUrl().getParameters().get("methods");
                boolean hasMethod = false;
                if (methodsStr == null || !methodsStr.contains(",")) {
                    hasMethod = inv.getMethodName().equals(methodsStr);
                } else {
                    String[] methods = methodsStr.split(",");
                    for (String method : methods) {
                        if (inv.getMethodName().equals(method)) {
                            hasMethod = true;
                            break;
                        }
                    }
                }
                if (!hasMethod) {
                    logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                            + " not found in callback service interface ,invoke will be ignored."
                            + " please update the api interface. url is:"
                            + invoker.getUrl()) + " ,invocation is :" + inv);
                    return null;
                }
            }
            RpcContext.getServiceContext().setRemoteAddress(channel.getRemoteAddress());
            /*
             *  那么问题 就是 Invoker对象的invoke方法 何时被调用？ 也就是什么时候在哪里收到  触发Invoker的invoke执行
             *  从而执行wrapper的invokeMethod，从而执行服务提供者的方法？
             *
             *  服务发布的时候虽然得到了Invoker对象，还需要使用Protocol对象对外发布
             *  Exporter<?> exporter = protocolSPI.export(invoker);--->protocolSPI 其实是Protocol$Adaptive 最终会执行DubboProtocol的export
             *  对于DubboProtocol而言，在其export方法中会创建一个DubboExporter对象，这个DubboExporter对象中持有Invoker对象。
             *  同时DubboProtocol的export在创建Export对象的时候 会将DubboProtocol对象的一个Map<String, Exporter<?>> exporterMap
             *  属性传递给DubboExporter对象，然后DubboExporter对象将自身放置到这个Map中。 这个map的key是serviceKey。
             *  export的时候还会创建一个nettyServer.
             *  DubboProtocol对象中有一个ExchangeHandler requestHandler， DubboProtocol的exptor方法会针对不同的服务只开启一个nettyServer。
             *  在创建NettyServer的时候会 传递 requestHandler给NettyServer，因此当消息来临的时候就会执行 requestHandler的方法
             *
             *  当服务提供者端收到消息请求的时候会执行org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol#requestHandler 的reply方法。
             *  requestHandler首先从请求中取出serviceKey，然后在DubboProtocol对象的Map中根据serviceKey找到 DubboExporter对象，
             *  然后根据export对象找到Invoker对象，然后读取请求中要执行的方法名称和参数等信息封装成Invocation。最终执行Invoker的invoke方法。
             */
            Result result = invoker.invoke(inv);
            return result.thenApply(Function.identity());
        }

        @Override
        public void received(Channel channel, Object message) throws RemotingException {

            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);

            } else {
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isDebugEnabled()) {
                logger.debug("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, ON_DISCONNECT_KEY);
        }

        private void invoke(Channel channel, String methodKey) {
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    if (Boolean.TRUE.toString().equals(invocation.getAttachment(STUB_EVENT_KEY))) {
                        tryToGetStubService(channel, invocation);
                    }
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        private void tryToGetStubService(Channel channel, Invocation invocation) throws RemotingException {
            try {
                Invoker<?> invoker = getInvoker(channel, invocation);
            } catch (RemotingException e) {
                String serviceKey = serviceKey(
                    0,
                    (String) invocation.getObjectAttachments().get(PATH_KEY),
                    (String) invocation.getObjectAttachments().get(VERSION_KEY),
                    (String) invocation.getObjectAttachments().get(GROUP_KEY)
                );
                throw new RemotingException(channel, "The stub service[" + serviceKey + "] is not found, it may not be exported yet");
            }
        }

        /**
         * FIXME channel.getUrl() always binds to a fixed service, and this service is random.
         * we can choose to use a common service to carry onConnect event if there's no easy way to get the specific
         * service this connection is binding to.
         * @param channel
         * @param url
         * @param methodKey
         * @return
         */
        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            /**
             * 如果Url中不包含key则直接返回
             */
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }

            /**
             * 根据method创建Invocation对象
             *
             */
            RpcInvocation invocation = new RpcInvocation(url.getServiceModel(), method, url.getParameter(INTERFACE_KEY), "", new Class<?>[0], new Object[0]);
            invocation.setAttachment(PATH_KEY, url.getPath());
            invocation.setAttachment(GROUP_KEY, url.getGroup());
            invocation.setAttachment(INTERFACE_KEY, url.getParameter(INTERFACE_KEY));
            invocation.setAttachment(VERSION_KEY, url.getVersion());
            if (url.getParameter(STUB_EVENT_KEY, false)) {
                invocation.setAttachment(STUB_EVENT_KEY, Boolean.TRUE.toString());
            }

            return invocation;
        }
    };

    public DubboProtocol() {
    }

    /**
     * @deprecated Use {@link DubboProtocol#getDubboProtocol(ScopeModel)} instead
     */
    @Deprecated
    public static DubboProtocol getDubboProtocol() {
        return (DubboProtocol) ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME, false);
    }

    public static DubboProtocol getDubboProtocol(ScopeModel scopeModel) {
        return (DubboProtocol) scopeModel.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME, false);
    }

    @Override
    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }

    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke;
        boolean isStubServiceInvoke;
        int port = channel.getLocalAddress().getPort();
        String path = (String) inv.getObjectAttachments().get(PATH_KEY);

        //if it's stub service on client side(after enable stubevent, usually is set up onconnect or ondisconnect method)
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getObjectAttachments().get(STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            //when a stub service export to local, it usually can't be exposed to port
            port = 0;
        }

        // if it's callback service on client side
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            path += "." + inv.getObjectAttachments().get(CALLBACK_SERVICE_KEY);
            inv.getObjectAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }

        String serviceKey = serviceKey(
                port,
                path,
                (String) inv.getObjectAttachments().get(VERSION_KEY),
                (String) inv.getObjectAttachments().get(GROUP_KEY)
        );
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        if (exporter == null) {
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " +
                    ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + getInvocationWithoutData(inv));
        }

        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        checkDestroyed();
        URL url = invoker.getUrl();

        /**
         * Inovker到Exporter的转换
         *
         * 根据服务分组、版本、服务接口和暴露端口作为key用于关联具体服务Invoker
         */
        String key = serviceKey(url);
        /*
         *  那么问题 就是 Invoker对象的invoke方法 何时被调用？ 也就是什么时候在哪里收到  触发Invoker的invoke执行
         *  从而执行wrapper的invokeMethod，从而执行服务提供者的方法？
         *
         *  服务发布的时候虽然得到了Invoker对象，还需要使用Protocol对象对外发布
         *  Exporter<?> exporter = protocolSPI.export(invoker);--->protocolSPI 其实是Protocol$Adaptive 最终会执行DubboProtocol的export
         *  对于DubboProtocol而言，在其export方法中会创建一个DubboExporter对象，这个DubboExporter对象中持有Invoker对象。
         *  同时DubboProtocol的export在创建Export对象的时候 会将DubboProtocol对象的一个Map<String, Exporter<?>> exporterMap
         *  属性传递给DubboExporter对象，然后DubboExporter对象将自身放置到这个Map中。 这个map的key是serviceKey。
         *  export的时候还会创建一个nettyServer.
         *  DubboProtocol对象中有一个ExchangeHandler requestHandler， DubboProtocol的exptor方法会针对不同的服务只开启一个nettyServer。
         *  在创建NettyServer的时候会 传递 requestHandler给NettyServer，因此当消息来临的时候就会执行 requestHandler的方法
         *
         *  当服务提供者端收到消息请求的时候会执行org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol#requestHandler 的reply方法。
         *  requestHandler首先从请求中取出serviceKey，然后在DubboProtocol对象的Map中根据serviceKey找到 DubboExporter对象，
         *  然后根据export对象找到Invoker对象，然后读取请求中要执行的方法名称和参数等信息封装成Invocation。最终执行Invoker的invoke方法。
         */
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);

        //export a stub service for dispatching event
        Boolean isStubSupportEvent = url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackservice) {
            String stubServiceMethods = url.getParameter(STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }

            }
        }

        /**
         * 同一个机器的不同服务导出只会开启一个NettyServer
         */
        openServer(url);
        optimizeSerialization(url);

        return exporter;
    }

    private void openServer(URL url) {
        checkDestroyed();
        // find server.
        /**
         * 提供者的机器地址ip:port
         */
        String key = url.getAddress();
        /**
         * 只有服务提供者才会启动监听。
         *
         * 首先获取当前机器地址信息 作为key，然后判断当前是否为服务提供端。如果是，则以此key为key查看缓存serverMap中是否有对应的Server，
         * 如果没有则调用createServer方法来创建，否则返回缓存中的value。
         * 由于每个机器的ipport是唯一的，所以多个不同服务启动时只有第一个会被创建，，后面的服务都是直接从缓存中返回的
         *
         */
        // client can export a service which only for server to invoke
        boolean isServer = url.getParameter(IS_SERVER_KEY, true);
        if (isServer) {
            /**
             * key 是ipport，因此只会创建一个nettyServer
             *
             * 同一个协议暴露有很多接口，只有初次暴露的接口才需要打开端口监听
             *
             */
            ProtocolServer server = serverMap.get(key);
            if (server == null) {
                synchronized (this) {
                    server = serverMap.get(key);
                    if (server == null) {
                        serverMap.put(key, createServer(url));
                    }else {
                        server.reset(url);
                    }
                }
            } else {
                // server supports reset, use together with override
                server.reset(url);
            }
        }
    }

    private void checkDestroyed() {
        if (destroyed.get()) {
            throw new IllegalStateException(getClass().getSimpleName() + " is destroyed");
        }
    }

    private ProtocolServer createServer(URL url) {
        url = URLBuilder.from(url)
                // send readonly event when server closes, it's enabled by default
                .addParameterIfAbsent(CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString())
                // enable heartbeat by default
                .addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT))
                .addParameter(CODEC_KEY, DubboCodec.NAME)
                .build();
        String str = url.getParameter(SERVER_KEY, DEFAULT_REMOTING_SERVER);

        if (StringUtils.isNotEmpty(str) && !url.getOrDefaultFrameworkModel().getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);
        }

        ExchangeServer server;
        try {
            /**
             * 这个requestHandler 被传递到 HeaderExchanger 的bind方法中
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
             */
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }

        str = url.getParameter(CLIENT_KEY);
        if (StringUtils.isNotEmpty(str)) {
            Set<String> supportedTypes = url.getOrDefaultFrameworkModel().getExtensionLoader(Transporter.class).getSupportedExtensions();
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }

        DubboProtocolServer protocolServer = new DubboProtocolServer(server);
        loadServerProperties(protocolServer);
        return protocolServer;
    }

    private void optimizeSerialization(URL url) throws RpcException {
        String className = url.getParameter(OPTIMIZER_KEY, "");
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException("The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }

            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c);
            }

            optimizers.add(className);

        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);

        } catch (InstantiationException | IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);

        }
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        checkDestroyed();
        return protocolBindingRefer(type, url);
    }

    @Override
    public <T> Invoker<T> protocolBindingRefer(Class<T> serviceType, URL url) throws RpcException {
        checkDestroyed();
        optimizeSerialization(url);

        // create rpc invoker.
        /**
         * getClient方法创见美服务消费端的NettyClient对象。
         *
         * 在getClient方法内部会创建NettyClient。这里有三个注意点：
         * 第一点 由于一个服务提供者可以提供多个服务，那么消费者机器需要与同一个服务提供者机器提供的多个服务共享连接，还是与每个服务都建立一个连接？
         *
         * 第二点：消费端是启动时就与服务提供者机器建立好连接吗？
         *
         * 第三点：每个服务消费端与 服务提供者集群中的所有机器都有连接吗？
         * toRouters方法中 其内部是吧具体服务的所有服务提供者的URL信息转换为了Invoker，也就是说服务消费端与服务提供者的所有机器都有连接。
         *
         *
         * ===========
         * DubboProtocol的refer方法返回一个DubboInvoker. ProtocolFilterWrapper是DubboProtocol的包装类，
         * DubboProtocol返回的invoker 在ProtocolFilterWrapper的refer方法中 使用 buildInvokeChain 进行了装饰，使用一系列
         * Filter形成了责任链，DubboInvoker被放置到责任链的末尾
         *
         */
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
        invokers.add(invoker);

        return invoker;
    }

    private ExchangeClient[] getClients(URL url) {
        // whether to share connection
        /**
         * 不同服务是否共享连接。 因为一个服务提供者机器可以提供多个服务，消费者需要与同一个服务提供者机器提供的多个服务共享连接还是与每个服务
         * 都建立一个连接？
         *在默认情况下当消费端引用同一个服务提供者机器上的多个服务时，这些服务复用同一个Netty链接。
         */
        boolean useShareConnect = false;

        int connections = url.getParameter(CONNECTIONS_KEY, 0);
        List<ReferenceCountExchangeClient> shareClients = null;
        // if not configured, connection is shared, otherwise, one connection for one service
        /**
         * 如果没配置，则默认连接是共享的，否则每个服务单独有自己的链接。
         */
        if (connections == 0) {
            useShareConnect = true;

            /*
             * The xml configuration should have a higher priority than properties.
             * xml优先级高于属性配置
             */
            String shareConnectionsStr = url.getParameter(SHARE_CONNECTIONS_KEY, (String) null);
            connections = Integer.parseInt(StringUtils.isBlank(shareConnectionsStr) ? ConfigurationUtils.getProperty(url.getOrDefaultApplicationModel(), SHARE_CONNECTIONS_KEY,
                    DEFAULT_SHARE_CONNECTIONS) : shareConnectionsStr);
            /**
             * 获取共享NettyClient
             */
            shareClients = getSharedClient(url, connections);
        }

        /**
         * 初始化NettyClient
         */
        ExchangeClient[] clients = new ExchangeClient[connections];
        for (int i = 0; i < clients.length; i++) {
            /**
             * 共享则返回已经存在的
             */
            if (useShareConnect) {
                clients[i] = shareClients.get(i);

            } else {
                /**
                 * 否则创建新的
                 */
                clients[i] = initClient(url);
            }
        }

        return clients;
    }

    /**
     * Get shared connection
     *
     * @param url
     * @param connectNum connectNum must be greater than or equal to 1
     */
    @SuppressWarnings("unchecked")
    private List<ReferenceCountExchangeClient> getSharedClient(URL url, int connectNum) {
        String key = url.getAddress();

        Object clients = referenceClientMap.get(key);
        if (clients instanceof List) {
            List<ReferenceCountExchangeClient> typedClients = (List<ReferenceCountExchangeClient>) clients;
            if (checkClientCanUse(typedClients)) {
                batchClientRefIncr(typedClients);
                return typedClients;
            }
        }

        List<ReferenceCountExchangeClient> typedClients = null;

        synchronized (referenceClientMap) {
            for (; ; ) {
                clients = referenceClientMap.get(key);

                if (clients instanceof List) {
                    typedClients = (List<ReferenceCountExchangeClient>) clients;
                    if (checkClientCanUse(typedClients)) {
                        batchClientRefIncr(typedClients);
                        return typedClients;
                    } else {
                        referenceClientMap.put(key, PENDING_OBJECT);
                        break;
                    }
                } else if (clients == PENDING_OBJECT) {
                    try {
                        referenceClientMap.wait();
                    } catch (InterruptedException ignored) {
                    }
                } else {
                    referenceClientMap.put(key, PENDING_OBJECT);
                    break;
                }
            }
        }

        try {
            // connectNum must be greater than or equal to 1
            connectNum = Math.max(connectNum, 1);

            // If the clients is empty, then the first initialization is
            if (CollectionUtils.isEmpty(typedClients)) {
                typedClients = buildReferenceCountExchangeClientList(url, connectNum);
            } else {
                for (int i = 0; i < typedClients.size(); i++) {
                    ReferenceCountExchangeClient referenceCountExchangeClient = typedClients.get(i);
                    // If there is a client in the list that is no longer available, create a new one to replace him.
                    if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                        typedClients.set(i, buildReferenceCountExchangeClient(url));
                        continue;
                    }
                    referenceCountExchangeClient.incrementAndGetCount();
                }
            }
        } finally {
            synchronized (referenceClientMap) {
                if (typedClients == null) {
                    referenceClientMap.remove(key);
                } else {
                    referenceClientMap.put(key, typedClients);
                }
                referenceClientMap.notifyAll();
            }
        }
        return typedClients;

    }

    /**
     * Check if the client list is all available
     *
     * @param referenceCountExchangeClients
     * @return true-available，false-unavailable
     */
    private boolean checkClientCanUse(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return false;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            // As long as one client is not available, you need to replace the unavailable client with the available one.
            if (referenceCountExchangeClient == null || referenceCountExchangeClient.getCount() <= 0 || referenceCountExchangeClient.isClosed()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Increase the reference Count if we create new invoker shares same connection, the connection will be closed without any reference.
     *
     * @param referenceCountExchangeClients
     */
    private void batchClientRefIncr(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            if (referenceCountExchangeClient != null) {
                referenceCountExchangeClient.incrementAndGetCount();
            }
        }
    }

    /**
     * Bulk build client
     *
     * @param url
     * @param connectNum
     * @return
     */
    private List<ReferenceCountExchangeClient> buildReferenceCountExchangeClientList(URL url, int connectNum) {
        List<ReferenceCountExchangeClient> clients = new ArrayList<>();

        for (int i = 0; i < connectNum; i++) {
            clients.add(buildReferenceCountExchangeClient(url));
        }

        return clients;
    }

    /**
     * Build a single client
     *
     * @param url
     * @return
     */
    private ReferenceCountExchangeClient buildReferenceCountExchangeClient(URL url) {
        ExchangeClient exchangeClient = initClient(url);
        ReferenceCountExchangeClient client = new ReferenceCountExchangeClient(exchangeClient, DubboCodec.NAME);
        // read configs
        int shutdownTimeout = ConfigurationUtils.getServerShutdownTimeout(url.getScopeModel());
        client.setShutdownWaitTime(shutdownTimeout);
        return client;
    }

    /**
     * Create new connection
     *
     * @param url
     */
    private ExchangeClient initClient(URL url) {

        /**
         * Instance of url is InstanceAddressURL, so addParameter actually adds parameters into ServiceInstance,
         * which means params are shared among different services. Since client is shared among services this is currently not a problem.
         */
        String str = url.getParameter(CLIENT_KEY, url.getParameter(SERVER_KEY, DEFAULT_REMOTING_CLIENT));

        // BIO is not allowed since it has severe performance issue.
        if (StringUtils.isNotEmpty(str) && !url.getOrDefaultFrameworkModel().getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " + StringUtils.join(url.getOrDefaultFrameworkModel().getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        ExchangeClient client;
        try {
            // Replace InstanceAddressURL with ServiceConfigURL.
            url = new ServiceConfigURL(DubboCodec.NAME, url.getUsername(), url.getPassword(), url.getHost(), url.getPort(), url.getPath(),  url.getAllParameters());
            url = url.addParameter(CODEC_KEY, DubboCodec.NAME);
            // enable heartbeat by default
            url = url.addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT));

            // connection should be lazy  惰性链接
            if (url.getParameter(LAZY_CONNECT_KEY, false)) {
                client = new LazyConnectExchangeClient(url, requestHandler);
            } else {
                /**
                 * 及时链接
                 *
                 * 默认lazy为false，所以当消费者启动时就与提供者建立了链接。
                 *
                 * 注意这里的 requestHandler作为ChannelHandler 被传递得到NettyClient
                 * 也就是NettyTransporter 的connect方法
                 *
                 */
                client = Exchangers.connect(url, requestHandler);
            }

        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }

        return client;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Destroying protocol [" + this.getClass().getSimpleName() + "] ...");
        }
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ProtocolServer protocolServer = serverMap.remove(key);

            if (protocolServer == null) {
                continue;
            }

            RemotingServer server = protocolServer.getRemotingServer();

            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Closing dubbo server: " + server.getLocalAddress());
                }

                server.close(getServerShutdownTimeout(protocolServer));

            } catch (Throwable t) {
                logger.warn("Close dubbo server [" + server.getLocalAddress()+ "] failed: " + t.getMessage(), t);
            }
        }
        serverMap.clear();

        for (String key : new ArrayList<>(referenceClientMap.keySet())) {
            Object clients = referenceClientMap.remove(key);
            if (clients instanceof List) {
                List<ReferenceCountExchangeClient> typedClients = (List<ReferenceCountExchangeClient>) clients;

                if (CollectionUtils.isEmpty(typedClients)) {
                    continue;
                }

                for (ReferenceCountExchangeClient client : typedClients) {
                    closeReferenceCountExchangeClient(client);
                }
            }
        }
        referenceClientMap.clear();

        super.destroy();
    }

    /**
     * close ReferenceCountExchangeClient
     *
     * @param client
     */
    private void closeReferenceCountExchangeClient(ReferenceCountExchangeClient client) {
        if (client == null) {
            return;
        }

        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
            }

            client.close(client.getShutdownWaitTime());

            // TODO
            /*
             * At this time, ReferenceCountExchangeClient#client has been replaced with LazyConnectExchangeClient.
             * Do you need to call client.close again to ensure that LazyConnectExchangeClient is also closed?
             */

        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    /**
     * only log body in debugger mode for size & security consideration.
     *
     * @param invocation
     * @return
     */
    private Invocation getInvocationWithoutData(Invocation invocation) {
        if (logger.isDebugEnabled()) {
            return invocation;
        }
        if (invocation instanceof RpcInvocation) {
            RpcInvocation rpcInvocation = (RpcInvocation) invocation;
            rpcInvocation.setArguments(null);
            return rpcInvocation;
        }
        return invocation;
    }
}

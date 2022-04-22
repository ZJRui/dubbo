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
package org.apache.dubbo.rpc.cluster.filter;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.model.ScopeModelUtil;

import java.util.List;

import static org.apache.dubbo.common.constants.CommonConstants.REFERENCE_FILTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SERVICE_FILTER_KEY;

/**
 * ListenerProtocol
 */
@Activate(order = 100)
public class ProtocolFilterWrapper implements Protocol {

    private final Protocol protocol;

    public ProtocolFilterWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        /**
         * 如果是注册中心的url则直接跳过。 这是什么意思呢？
         *
         * RegistryProtocol  DubboProtocol都是protocol，ProtocolFilterWrapper 都会对这两个Protocol进行wrapper
         * 当RegistryProtocol和DubboProtocol的export方法执行之前都会先执行ProtocolFilterWrapper的export
         * 但是从这里我们 看到ProtocolFilterWrapper对 RegistryProtocol没有 做什么特别的处理，而是直接跳过了
         */
        if (UrlUtils.isRegistry(invoker.getUrl())) {
            return protocol.export(invoker);
        }
        /**
         * 在服务暴露前（比如RegistryProtocol的export执行前）
         * ProtocolListenerWrapper实现中 在对服务提供者进行暴露时回调对应的监听方法
         *
         * 因为协议的包装顺序是 ProtocolListenerWrapper包装了协议实现类DubboProtocol。然后ProtocolFilterWrapper包装了ProtocolListenerWrapper
         *
         * 因此在执行export的时候先 执行ProtocolFIlterWrapper.export ，ProtocolFilterWrapper会调用 ProtocolListenerWrapper的export方法。
         *
         *-----------------------------
         * builder.buildInvokerChain中会构建一个Filter调用链。
         *
         *
         */
        FilterChainBuilder builder = getFilterChainBuilder(invoker.getUrl());
        /**
         * 先构造拦截器链（会过滤Provider端分组），然后出发dubbo协议暴露
         * 出发dubbo协议暴露前先对服务Invoker（也就是ProxyFactory的getInvoker方法返回的AbstractProxyInvoker）做一层拦截器构建。将
         * AbstractProxyInvoker挂载到拦截器链尾部，然后逐层包裹其他拦截器。
         * 构造拦截器之后会调用Dubbo协议进行暴露，DubboProtocol协议的export内创建DubboExporter，这个DubboExporter对象持有拦截器链的最外层Invoker
         * 因此通过DubboExporter就可以得到拦截器链，然后触发拦截器链执行 最终执行AbstractProxyInvoker的invoke
         *
         * 实际上 这里的 ProtocolFilterWrapper包装了ProtocolListenerWrapper ，因此下面的protocol是ProtocolListenerWrapper
         */
        return protocol.export(builder.buildInvokerChain(invoker, SERVICE_FILTER_KEY, CommonConstants.PROVIDER));
    }

    private <T> FilterChainBuilder getFilterChainBuilder(URL url) {
        return ScopeModelUtil.getExtensionLoader(FilterChainBuilder.class, url.getScopeModel()).getDefaultExtension();
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        /**
         * 如果是注册中心的url则直接跳过。 这是什么意思呢？
         *
         * RegistryProtocol  DubboProtocol都是protocol，ProtocolFilterWrapper 都会对这两个Protocol进行wrapper
         * 当RegistryProtocol和DubboProtocol的export方法执行之前都会先执行ProtocolFilterWrapper的export
         * 但是从这里我们 看到ProtocolFilterWrapper对 RegistryProtocol没有 做什么特别的处理，而是直接跳过了
         */
        if (UrlUtils.isRegistry(url)) {
            return protocol.refer(type, url);
        }
        FilterChainBuilder builder = getFilterChainBuilder(url);
        /**
         *DubboProtocol的refer方法返回一个DubboInvoker. ProtocolFilterWrapper是DubboProtocol的包装类，
         * DubboProtocol返回的invoker 在ProtocolFilterWrapper的refer方法中 使用 buildInvokeChain 进行了装饰，使用一系列
         * Filter形成了责任链，DubboInvoker被放置到责任链的末尾
         *
         * 下面代码先执行 protocol.refer
         * buildInvokerChain方法返回的是调用链对象 CallbackRegistrationInvoker
         *
         * ==============================
         *
         * 首先ReferenceConfig的get方法会触发 创建一个接口的代理对象，在ReferenceConfig的crateProxy方法中首先是需要创建一个Invoker对象
         * ReferenceConfig的createInvokerFromRemote方法内部会执行  invoker = protocolSPI.refer(interfaceClass, curUrl);
         * 这个ProtocolSPI显然是Protocol$Adaptive 然后因为注册协议是registry:// 因此会执行RegistryProtocol的refer方法。
         * 在RegistryProtocol的refer方法中会触发 RegistryProtocol的getInvoker 方法执行 .
         * 在getInvoker方法中 首先创建一个 RegistryDirectory对象，这个RegistryDirectory内部会维护并创建Invoker对象。
         *
         * RegistryDirectory对象创建完之后会执行 其subscribe方法 subscribe方法中使用Zookeeperregistry.subscribe(url, this);
         * 然后就会执行 ZookeeperRegistry的doSubscribe 在这个doSubscribe方法中会使用zkclient连接Zookeeper获取服务提供者的url信息，然后执行
         * ZookeeperRegistry的notify(url, listener, urls);  在这个notify方法中会遍历每一个 Listener，执行Listener的notify方法。
         * 而RegistryDirectory又是一个listener，因此又进入了RegistryDirectory的notify方法
         * 在RegistryDirectory的notify方法中会执行 toInvokers方法 ，toInvoker方法中使用 RegistryDirectory对象的成员属性protocol执行：
         *  invoker = protocol.refer(serviceType, url);
         *  而这个Protocol就是DubboProtocol。 因此RegistryDirectory中得到了 DubboProtocol的refer方法中返回读DubboInvoker。而且DubboProtocol在
         *  创建DubboInvoker的时候会启动NettyClient 连接到服务提供者NettyServer。
         *
         *  在上面有一个注意点： 实际上 RegistryDirectory创建Invoker的时候不是直接使用DubboProtocol的refer，而是先使用了ProtocolFilterWrapper的refer
         *  在ProtocolFilterWrapper的refer方法中 对DubboProtocol的refer返回的DubboInvoker对象 进行了包装，构建成了一个调用链。ProtocolFilterWrapper
         *  的refer方法返回的是CallbackRegistrationInvoker调用链对象，这个调用链对象同时也是一个Invoker。因此RegistryDirectory内部维护的InvokerList内部的
         *  Invoker对象实际上一个个调用链CallbackRegistrationInvoker，调用链的最末尾就是DubboInvoker。
         *
         *  至此程序完成了RegistryDirectory的subscribe方法，因此回溯到 RegistryProtocol的doCreateInvoker方法，在doCreateInvoker方法中
         *  先执行了RegistryDirectory的subscribe方法使得在RegistryDirectory内部创建了Invoker。然后又针对 RegistryDirectory对象执行了
         *  cluster.join(directory, true);
         *
         *  其中这个Cluster是SPI扩展接口，实际上的调用时序是ClusterAdaptive.join---->MockClusterWrapper.join--->FailoverCluster.join
         *  我们查看FailOverCluster的join方法，可以看到其doJoin方法返回了一个 FailoverClusterInvoker, 而且这个FailOverClusterInvoker对象
         *  持有RegistryDirectory对象，也就等价于持有服务提供者Invoker。  FailOverCluster的join方法返回的FailOverClusterInvoker对象又被交给
         *  MockClusterWrapper，他的join方法中将FailOverClusterInvoker包装成MockClusterInvoker 。因此 最终 这个mockClusterInvoker 作为
         *  RegistryProtocol的refer方法的返回值， 也就是说ReferenceConfig的createProxy的第一步invoker = protocolSPI.refer(interfaceClass, curUrl);
         *  得到了一个MockClusterInvoker。
         *
         *
         *
         *  然后ReferenceConfig的createProxy的第二步就是针对 上一步返回的Invoker对象 创建一个业务接口的代理对象 ，实现将Invoker转为业务接口代理对象
         *  这个转换是 proxyFactory.getProxy(invoker, ProtocolUtils.isGeneric(generic)); 这会执行JavassistProxyFactory的getProxy方法
         *  返回一个代理对象，getProxy中指定了拦截器为 InvokerInvocationHandler ，在创建InvokerInvocationHandler对象的时候我们将 上面返回的MockClusterInvoker交给了拦截器
         *
         *  因此当接口方法执行的时候会被拦截到执行InvokerInvocationHandler的invoke方法，在拦截器的在invoke方法内部会执行MockClusterInvoker的invoke,调用顺序如下：
         *
         *  InvokerInvocationHandler.invoke--->MockClusterInvocker.invoke-->FailoverClusterInvoker.invoke  FailOverClusterInvoker内部有RegistryDirectory，他会
         *  负载均衡选择一个Invoker进行执行，而每一个Invoker本质上是ProtocolFilterWrapper返回的调用链对象--->ProtocolFIlterWrapper的refer返回的CallbackRegistrationInvoker.invoker
         *  执行调用链--->DubboInvoker.invoke.doInvoke 将请求发送给服务提供者的NettyServer
         *
         *
         *
         *
         */
        return builder.buildInvokerChain(protocol.refer(type, url), REFERENCE_FILTER_KEY, CommonConstants.CONSUMER);
    }

    @Override
    public void destroy() {
        protocol.destroy();
    }

    @Override
    public List<ProtocolServer> getServers() {
        return protocol.getServers();
    }

}

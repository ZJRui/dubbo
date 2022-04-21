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
package org.apache.dubbo.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.constants.RegistryConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.metadata.ServiceNameMapping;
import org.apache.dubbo.registry.client.metadata.MetadataUtils;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.cluster.support.registry.ZoneAwareCluster;
import org.apache.dubbo.rpc.model.AsyncMethodInfo;
import org.apache.dubbo.rpc.model.ConsumerModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ModuleServiceRepository;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.stub.StubSuppliers;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR_CHAR;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROXY_CLASS_REF;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SEMICOLON_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SUBSCRIBED_SERVICE_NAMES_KEY;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.StringUtils.splitToSet;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.registry.Constants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTER_IP_KEY;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;
import static org.apache.dubbo.rpc.cluster.Constants.PEER_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

/**
 * Please avoid using this class for any new application,
 * use {@link ReferenceConfigBase} instead.
 */
public class ReferenceConfig<T> extends ReferenceConfigBase<T> {

    public static final Logger logger = LoggerFactory.getLogger(ReferenceConfig.class);

    /**
     * The {@link Protocol} implementation with adaptive functionality,it will be different in different scenarios.
     * A particular {@link Protocol} implementation is determined by the protocol attribute in the {@link URL}.
     * For example:
     *
     * <li>when the url is registry://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=dubbo-sample,
     * then the protocol is <b>RegistryProtocol</b></li>
     *
     * <li>when the url is dubbo://224.5.6.7:1234/org.apache.dubbo.config.api.DemoService?application=dubbo-sample, then
     * the protocol is <b>DubboProtocol</b></li>
     * <p>
     * Actually，when the {@link ExtensionLoader} init the {@link Protocol} instants,it will automatically wrap three
     * layers, and eventually will get a <b>ProtocolSerializationWrapper</b> or <b>ProtocolFilterWrapper</b> or <b>ProtocolListenerWrapper</b>
     */
    private Protocol protocolSPI;

    /**
     * A {@link ProxyFactory} implementation that will generate a reference service's proxy,the JavassistProxyFactory is
     * its default implementation
     */
    private ProxyFactory proxyFactory;

    private ConsumerModel consumerModel;

    /**
     * The interface proxy reference
     */
    private transient volatile T ref;

    /**
     * The invoker of the reference service
     */
    private transient volatile Invoker<?> invoker;

    /**
     * The flag whether the ReferenceConfig has been initialized
     */
    private transient volatile boolean initialized;

    /**
     * whether this ReferenceConfig has been destroyed
     */
    private transient volatile boolean destroyed;

    /**
     * The service names that the Dubbo interface subscribed.
     *
     * @since 2.7.8
     */
    private String services;

    public ReferenceConfig() {
        super();
    }

    public ReferenceConfig(ModuleModel moduleModel) {
        super(moduleModel);
    }

    public ReferenceConfig(Reference reference) {
        super(reference);
    }

    public ReferenceConfig(ModuleModel moduleModel, Reference reference) {
        super(moduleModel, reference);
    }

    @Override
    protected void postProcessAfterScopeModelChanged(ScopeModel oldScopeModel, ScopeModel newScopeModel) {
        super.postProcessAfterScopeModelChanged(oldScopeModel, newScopeModel);

        /**
         * 在Protocol$Adaptive的refer方法内部，当我们设置了服务注册中心后，可以发现当前协议类型为registry
         * ,也就是说这里需要调用RegistryProtocol的refer方法。但是RegistryProtocol被QosProtocolWrapper ProtocolFilterWrapper
         * ProtocolListenerWrapper三个Wrapper类增强了。所以经过层层调用才会执行RegistryProtocol的refer方法
         */
        protocolSPI = this.getExtensionLoader(Protocol.class).getAdaptiveExtension();
        proxyFactory = this.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    }

    /**
     * Get a string presenting the service names that the Dubbo interface subscribed.
     * If it is a multiple-values, the content will be a comma-delimited String.
     *
     * @return non-null
     * @see RegistryConstants#SUBSCRIBED_SERVICE_NAMES_KEY
     * @since 2.7.8
     */
    @Deprecated
    @Parameter(key = SUBSCRIBED_SERVICE_NAMES_KEY)
    public String getServices() {
        return services;
    }

    /**
     * It's an alias method for {@link #getServices()}, but the more convenient.
     *
     * @return the String {@link List} presenting the Dubbo interface subscribed
     * @since 2.7.8
     */
    @Deprecated
    @Parameter(excluded = true)
    public Set<String> getSubscribedServices() {
        return splitToSet(getServices(), COMMA_SEPARATOR_CHAR);
    }

    /**
     * Set the service names that the Dubbo interface subscribed.
     *
     * @param services If it is a multiple-values, the content will be a comma-delimited String.
     * @since 2.7.8
     */
    public void setServices(String services) {
        this.services = services;
    }

    @Override
    public T get() {
        if (destroyed) {
            throw new IllegalStateException("The invoker of ReferenceConfig(" + url + ") has already destroyed!");
        }

        if (ref == null) {
            // ensure start module, compatible with old api usage
            getScopeModel().getDeployer().start();

            synchronized (this) {
                if (ref == null) {
                    init();
                }
            }
        }

        return ref;
    }

    @Override
    public synchronized void destroy() {
        super.destroy();
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            if (invoker != null) {
                invoker.destroy();
            }
        } catch (Throwable t) {
            logger.warn("Unexpected error occurred when destroy invoker of ReferenceConfig(" + url + ").", t);
        }
        invoker = null;
        ref = null;
        if (consumerModel != null) {
            ModuleServiceRepository repository = getScopeModel().getServiceRepository();
            repository.unregisterConsumer(consumerModel);
        }
    }

    protected synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        if (!this.isRefreshed()) {
            this.refresh();
        }

        // init serviceMetadata
        initServiceMetadata(consumer);

        serviceMetadata.setServiceType(getServiceInterfaceClass());
        // TODO, uncomment this line once service key is unified
        serviceMetadata.setServiceKey(URL.buildKey(interfaceName, group, version));

        Map<String, String> referenceParameters = appendConfig();
        // init service-application mapping
        initServiceAppsMapping(referenceParameters);

        ModuleServiceRepository repository = getScopeModel().getServiceRepository();
        ServiceDescriptor serviceDescriptor;
        if (CommonConstants.NATIVE_STUB.equals(getProxy())) {
            serviceDescriptor = StubSuppliers.getServiceDescriptor(interfaceName);
            repository.registerService(serviceDescriptor);
        } else {
            serviceDescriptor = repository.registerService(interfaceClass);
        }
        consumerModel = new ConsumerModel(serviceMetadata.getServiceKey(), proxy, serviceDescriptor, this,
            getScopeModel(), serviceMetadata, createAsyncMethodInfo());

        repository.registerConsumer(consumerModel);

        serviceMetadata.getAttachments().putAll(referenceParameters);

        /**
         * 创建代理
         */
        ref = createProxy(referenceParameters);

        serviceMetadata.setTarget(ref);
        serviceMetadata.addAttribute(PROXY_CLASS_REF, ref);

        consumerModel.setProxyObject(ref);
        consumerModel.initMethodModels();

        /**
         * 是否应该在启动时检查提供方是否可用
         */
        checkInvokerAvailable();
    }

    private void initServiceAppsMapping(Map<String, String> referenceParameters) {
        ServiceNameMapping serviceNameMapping = ServiceNameMapping.getDefaultExtension(getScopeModel());
        URL url = new ServiceConfigURL(LOCAL_PROTOCOL, LOCALHOST_VALUE, 0, interfaceName, referenceParameters);
        serviceNameMapping.initInterfaceAppMapping(url);
    }

    /**
     * convert and aggregate async method info
     *
     * @return Map<String, AsyncMethodInfo>
     */
    private Map<String, AsyncMethodInfo> createAsyncMethodInfo() {
        Map<String, AsyncMethodInfo> attributes = null;
        if (CollectionUtils.isNotEmpty(getMethods())) {
            attributes = new HashMap<>(16);
            for (MethodConfig methodConfig : getMethods()) {
                AsyncMethodInfo asyncMethodInfo = methodConfig.convertMethodConfig2AsyncInfo();
                if (asyncMethodInfo != null) {
                    attributes.put(methodConfig.getName(), asyncMethodInfo);
                }
            }
        }

        return attributes;
    }

    /**
     * Append all configuration required for service reference.
     *
     * @return reference parameters
     */
    private Map<String, String> appendConfig() {
        Map<String, String> map = new HashMap<>(16);

        map.put(INTERFACE_KEY, interfaceName);
        map.put(SIDE_KEY, CONSUMER_SIDE);

        ReferenceConfigBase.appendRuntimeParameters(map);

        if (!ProtocolUtils.isGeneric(generic)) {
            String revision = Version.getVersion(interfaceClass, version);
            if (StringUtils.isNotEmpty(revision)) {
                map.put(REVISION_KEY, revision);
            }

            String[] methods = methods(interfaceClass);
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                map.put(METHODS_KEY, StringUtils.join(new HashSet<>(Arrays.asList(methods)), COMMA_SEPARATOR));
            }
        }

        AbstractConfig.appendParameters(map, getApplication());
        AbstractConfig.appendParameters(map, getModule());
        AbstractConfig.appendParameters(map, consumer);
        AbstractConfig.appendParameters(map, this);
        appendMetricsCompatible(map);

        String hostToRegistry = ConfigUtils.getSystemProperty(DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {
            hostToRegistry = NetUtils.getLocalHost();
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException(
                "Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }

        map.put(REGISTER_IP_KEY, hostToRegistry);

        if (CollectionUtils.isNotEmpty(getMethods())) {
            for (MethodConfig methodConfig : getMethods()) {
                AbstractConfig.appendParameters(map, methodConfig, methodConfig.getName());
                String retryKey = methodConfig.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(methodConfig.getName() + ".retries", "0");
                    }
                }
            }
        }

        return map;
    }

    @SuppressWarnings({"unchecked"})
    private T createProxy(Map<String, String> referenceParameters) {
        /**
         * 是否需要打开本地引用
         *
         */
        if (shouldJvmRefer(referenceParameters)) {
            createInvokerForLocal(referenceParameters);
        } else {
            urls.clear();
            if (StringUtils.isNotEmpty(url)) {
                // user specified URL, could be peer-to-peer address, or register center's address.
                /**
                 * 用户是否制定服务提供方地址：可以是服务提供方ip地址（直连方式）
                 */
                parseUrl(referenceParameters);
            } else {
                // if protocols not in jvm checkRegistry
                if (!LOCAL_PROTOCOL.equalsIgnoreCase(getProtocol())) {
                    aggregateUrlFromRegistry(referenceParameters);
                }
            }
            createInvokerForRemote();
        }

        if (logger.isInfoEnabled()) {
            logger.info("Referred dubbo service: [" + referenceParameters.get(INTERFACE_KEY) + "]." +
                (Boolean.parseBoolean(referenceParameters.get(GENERIC_KEY)) ?
                    " it's GenericService reference" : " it's not GenericService reference"));
        }

        URL consumerUrl = new ServiceConfigURL(CONSUMER_PROTOCOL, referenceParameters.get(REGISTER_IP_KEY), 0,
            referenceParameters.get(INTERFACE_KEY), referenceParameters);
        consumerUrl = consumerUrl.setScopeModel(getScopeModel());
        consumerUrl = consumerUrl.setServiceModel(consumerModel);
        MetadataUtils.publishServiceDefinition(consumerUrl, consumerModel.getServiceModel(), getApplicationModel());

        /**
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
         *  ===================================
         *
         *
         */



        // create service proxy
        /**
         * 创建服务代理对象。
         * 在服务提供方：proxyF.getInvoker的时候传递的是 服务提供者对象实例得到的是Invoker对象
         * 而消费端： proxyFactory getProxy传递的是Invoker对象，返回的是服务提供者代理对象
         */
        return (T) proxyFactory.getProxy(invoker, ProtocolUtils.isGeneric(generic));
    }

    /**
     * Make a local reference, create a local invoker.
     *
     * @param referenceParameters
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void createInvokerForLocal(Map<String, String> referenceParameters) {
        URL url = new ServiceConfigURL(LOCAL_PROTOCOL, LOCALHOST_VALUE, 0, interfaceClass.getName(), referenceParameters);
        url = url.setScopeModel(getScopeModel());
        url = url.setServiceModel(consumerModel);
        Invoker<?> withFilter = protocolSPI.refer(interfaceClass, url);
        // Local Invoke ( Support Cluster Filter / Filter )
        List<Invoker<?>> invokers = new ArrayList<>();
        invokers.add(withFilter);
        invoker = Cluster.getCluster(url.getScopeModel(), Cluster.DEFAULT).join(new StaticDirectory(url, invokers), true);

        if (logger.isInfoEnabled()) {
            logger.info("Using in jvm service " + interfaceClass.getName());
        }
    }

    /**
     * Parse the directly configured url.
     */
    private void parseUrl(Map<String, String> referenceParameters) {
        String[] us = SEMICOLON_SPLIT_PATTERN.split(url);
        if (ArrayUtils.isNotEmpty(us)) {
            for (String u : us) {
                URL url = URL.valueOf(u);
                if (StringUtils.isEmpty(url.getPath())) {
                    url = url.setPath(interfaceName);
                }
                url = url.setScopeModel(getScopeModel());
                url = url.setServiceModel(consumerModel);
                if (UrlUtils.isRegistry(url)) {
                    urls.add(url.putAttribute(REFER_KEY, referenceParameters));
                } else {
                    URL peerUrl = getScopeModel().getApplicationModel().getBeanFactory().getBean(ClusterUtils.class).mergeUrl(url, referenceParameters);
                    peerUrl = peerUrl.putAttribute(PEER_KEY, true);
                    urls.add(peerUrl);
                }
            }
        }
    }

    /**
     * Get URLs from the registry and aggregate them.
     */
    private void aggregateUrlFromRegistry(Map<String, String> referenceParameters) {
        /**
         * 根据服务注册中心装配Url对象
         */
        checkRegistry();


        List<URL> us = ConfigValidationUtils.loadRegistries(this, false);
        if (CollectionUtils.isNotEmpty(us)) {
            for (URL u : us) {
                URL monitorUrl = ConfigValidationUtils.loadMonitor(this, u);
                if (monitorUrl != null) {
                    u = u.putAttribute(MONITOR_KEY, monitorUrl);
                }
                u = u.setScopeModel(getScopeModel());
                u = u.setServiceModel(consumerModel);
                urls.add(u.putAttribute(REFER_KEY, referenceParameters));
            }
        }
        if (urls.isEmpty()) {
            throw new IllegalStateException(
                "No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() +
                    " use dubbo version " + Version.getVersion() +
                    ", please config <dubbo:registry address=\"...\" /> to your spring config.");
        }
    }


    /**
     * Make a remote reference, create a remote reference invoker
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void createInvokerForRemote() {
        /**
         * 只有一个服务中心时候
         */
        if (urls.size() == 1) {
            URL curUrl = urls.get(0);
            /**
             * 服务暴露的第一步是调用Protocol扩展接口实现类的refer方法生成Invoker实例
             *
             * 在Protocol$Adaptive的refer方法内部，当我们设置了服务注册中心后，可以发现当前协议类型为registry
             * ,也就是说这里需要调用RegistryProtocol的refer方法。但是RegistryProtocol被QosProtocolWrapper ProtocolFilterWrapper
             * ProtocolListenerWrapper三个Wrapper类增强了。所以经过层层调用才会执行RegistryProtocol的refer方法
             *
             * 跟进去的调用流程是：
             *
             * ProtocolListenerWrapper.refer方法 --------> ProtocolFilterWrapper.refer --------> QosProtocolWrapper.refer ----
             * ----> QosProtocolWrapper.startQosServer(url); --------> RegistryProtocol.refer --------> RegistryProtocol.doRefer
             *
             * --------> MockClusterWrapper.join ------> FailoverCluster.join ------> ProviderConsumerRegTable.registerConsumer
             *
             *
             *
             */

            invoker = protocolSPI.refer(interfaceClass, curUrl);
            if (!UrlUtils.isRegistry(curUrl)) {
                List<Invoker<?>> invokers = new ArrayList<>();
                invokers.add(invoker);
                invoker = Cluster.getCluster(scopeModel, Cluster.DEFAULT).join(new StaticDirectory(curUrl, invokers), true);
            }
        } else {
            List<Invoker<?>> invokers = new ArrayList<>();
            URL registryUrl = null;
            for (URL url : urls) {
                // For multi-registry scenarios, it is not checked whether each referInvoker is available.
                // Because this invoker may become available later.
                invokers.add(protocolSPI.refer(interfaceClass, url));

                if (UrlUtils.isRegistry(url)) {
                    // use last registry url
                    registryUrl = url;
                }
            }

            if (registryUrl != null) {
                // registry url is available
                // for multi-subscription scenario, use 'zone-aware' policy by default
                String cluster = registryUrl.getParameter(CLUSTER_KEY, ZoneAwareCluster.NAME);
                // The invoker wrap sequence would be: ZoneAwareClusterInvoker(StaticDirectory) -> FailoverClusterInvoker
                // (RegistryDirectory, routing happens here) -> Invoker
                invoker = Cluster.getCluster(registryUrl.getScopeModel(), cluster, false).join(new StaticDirectory(registryUrl, invokers), false);
            } else {
                // not a registry url, must be direct invoke.
                if (CollectionUtils.isEmpty(invokers)) {
                    throw new IllegalArgumentException("invokers == null");
                }
                URL curUrl = invokers.get(0).getUrl();
                String cluster = curUrl.getParameter(CLUSTER_KEY, Cluster.DEFAULT);
                invoker = Cluster.getCluster(scopeModel, cluster).join(new StaticDirectory(curUrl, invokers), true);
            }
        }
    }

    private void checkInvokerAvailable() throws IllegalStateException {
        if (shouldCheck() && !invoker.isAvailable()) {
            invoker.destroy();
            throw new IllegalStateException("Failed to check the status of the service "
                + interfaceName
                + ". No provider available for the service "
                + (group == null ? "" : group + "/")
                + interfaceName +
                (version == null ? "" : ":" + version)
                + " from the url "
                + invoker.getUrl()
                + " to the consumer "
                + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());
        }
    }

    /**
     * This method should be called right after the creation of this class's instance, before any property in other config modules is used.
     * Check each config modules are created properly and override their properties if necessary.
     */
    protected void checkAndUpdateSubConfigs() {
        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
        }

        // get consumer's global configuration
        completeCompoundConfigs();

        // init some null configuration.
        List<ConfigInitializer> configInitializers = this.getExtensionLoader(ConfigInitializer.class)
            .getActivateExtension(URL.valueOf("configInitializer://"), (String[]) null);
        configInitializers.forEach(e -> e.initReferConfig(this));

        if (getGeneric() == null && getConsumer() != null) {
            setGeneric(getConsumer().getGeneric());
        }
        if (ProtocolUtils.isGeneric(generic)) {
            if (interfaceClass != null && !interfaceClass.equals(GenericService.class)) {
                logger.warn(String.format("Found conflicting attributes for interface type: [interfaceClass=%s] and [generic=%s], " +
                        "because the 'generic' attribute has higher priority than 'interfaceClass', so change 'interfaceClass' to '%s'. " +
                        "Note: it will make this reference bean as a candidate bean of type '%s' instead of '%s' when resolving dependency in Spring.",
                    interfaceClass.getName(), generic, GenericService.class.getName(), GenericService.class.getName(), interfaceClass.getName()));
            }
            interfaceClass = GenericService.class;
        } else {
            try {
                if (getInterfaceClassLoader() != null && (interfaceClass == null || interfaceClass.getClassLoader() != getInterfaceClassLoader())) {
                    interfaceClass = Class.forName(interfaceName, true, getInterfaceClassLoader());
                } else if (interfaceClass == null) {
                    interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        checkStubAndLocal(interfaceClass);
        ConfigValidationUtils.checkMock(interfaceClass, this);

        resolveFile();
        ConfigValidationUtils.validateReferenceConfig(this);
        postProcessConfig();
    }

    @Override
    protected void postProcessRefresh() {
        super.postProcessRefresh();
        checkAndUpdateSubConfigs();
    }

    protected void completeCompoundConfigs() {
        super.completeCompoundConfigs(consumer);
        if (consumer != null) {
            if (StringUtils.isEmpty(registryIds)) {
                setRegistryIds(consumer.getRegistryIds());
            }
        }
    }

    /**
     * Figure out should refer the service in the same JVM from configurations. The default behavior is true
     * 1. if injvm is specified, then use it
     * 2. then if a url is specified, then assume it's a remote call
     * 3. otherwise, check scope parameter
     * 4. if scope is not specified but the target service is provided in the same JVM, then prefer to make the local
     * call, which is the default behavior
     */
    protected boolean shouldJvmRefer(Map<String, String> map) {
        URL tmpUrl = new ServiceConfigURL("temp", "localhost", 0, map);
        boolean isJvmRefer;
        if (isInjvm() == null) {
            // if an url is specified, don't do local reference
            if (StringUtils.isNotEmpty(url)) {
                isJvmRefer = false;
            } else {
                // by default, reference local service if there is
                isJvmRefer = InjvmProtocol.getInjvmProtocol(getScopeModel()).isInjvmRefer(tmpUrl);
            }
        } else {
            isJvmRefer = isInjvm();
        }
        return isJvmRefer;
    }

    private void postProcessConfig() {
        List<ConfigPostProcessor> configPostProcessors = this.getExtensionLoader(ConfigPostProcessor.class)
            .getActivateExtension(URL.valueOf("configPostProcessor://"), (String[]) null);
        configPostProcessors.forEach(component -> component.postProcessReferConfig(this));
    }

    /**
     * just for test
     *
     * @return
     */
    @Deprecated
    public Invoker<?> getInvoker() {
        return invoker;
    }
}

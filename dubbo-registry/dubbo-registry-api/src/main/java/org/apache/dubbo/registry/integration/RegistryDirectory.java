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
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.url.component.DubboServiceAddressURL;
import org.apache.dubbo.common.url.component.ServiceAddressURL;
import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.AddressListener;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.router.state.BitList;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ModuleModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DISABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_PROTOCOL;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TAG_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.APP_DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.COMPATIBLE_CONFIG_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_HASHMAP_LOAD_FACTOR;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTE_PROTOCOL;
import static org.apache.dubbo.registry.Constants.CONFIGURATORS_SUFFIX;
import static org.apache.dubbo.rpc.Constants.MOCK_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.ROUTER_KEY;
import static org.apache.dubbo.rpc.model.ScopeModelUtil.getApplicationModel;


/**
 * RegistryDirectory
 */
public class RegistryDirectory<T> extends DynamicDirectory<T> {
    private static final Logger logger = LoggerFactory.getLogger(RegistryDirectory.class);

    private final ConsumerConfigurationListener consumerConfigurationListener;
    private ReferenceConfigurationListener referenceConfigurationListener;

    /**
     * Map<url, Invoker> cache service url to invoker mapping.
     * The initial value is null and the midway may be assigned to null, please use the local variable reference
     *
     * 在RegistryDirectory中维护了所有服务者的invoker列表，消费端发起远程调用时就根据集群容错和负载均衡算法以及
     * 路由规则从invoker列表中选择一个进行调用，当服务提供者宕机的时候，Zookeeper会通知更新这个invoker列表
     */
    protected volatile Map<URL, Invoker<T>> urlInvokerMap;

    /**
     * The initial value is null and the midway may be assigned to null, please use the local variable reference
     */
    protected volatile Set<URL> cachedInvokerUrls;
    private final ApplicationModel applicationModel;

    public RegistryDirectory(Class<T> serviceType, URL url) {
        super(serviceType, url);
        applicationModel = getApplicationModel(url.getScopeModel());
        consumerConfigurationListener = getConsumerConfigurationListener(url.getOrDefaultModuleModel());
    }

    @Override
    public void subscribe(URL url) {
        super.subscribe(url);
        /**
         *
         * subscribe:159, RegistryDirectory (com.alibaba.dubbo.registry.integration)
         * doRefer:305, RegistryProtocol (com.alibaba.dubbo.registry.integration)
         * refer:286, RegistryProtocol (com.alibaba.dubbo.registry.integration)
         * refer:61, GroupProtocolWrapper (com.yupaopao.framework.spring.boot.dubbo.protocal)
         * refer:65, ProtocolListenerWrapper (com.alibaba.dubbo.rpc.protocol)
         * refer:106, ProtocolFilterWrapper (com.alibaba.dubbo.rpc.protocol)
         * refer:63, QosProtocolWrapper (com.alibaba.dubbo.qos.protocol)
         * refer:-1, Protocol$Adaptive (com.alibaba.dubbo.rpc)
         * createProxy:399, ReferenceConfig (com.alibaba.dubbo.config)
         * init:333, ReferenceConfig (com.alibaba.dubbo.config)
         * get:163, ReferenceConfig (com.alibaba.dubbo.config)
         * getObject:66, ReferenceBean (com.alibaba.dubbo.config.spring)
         * invoke0:-1, NativeMethodAccessorImpl (sun.reflect)
         * invoke:62, NativeMethodAccessorImpl (sun.reflect)
         * invoke:43, DelegatingMethodAccessorImpl (sun.reflect)
         * invoke:498, Method (java.lang.reflect)
         * toString:474, AbstractConfig (com.alibaba.dubbo.config)
         * valueOf:2994, String (java.lang)
         * append:137, StringBuilder (java.lang)
         * build:79, AbstractAnnotationConfigBeanBuilder (com.alibaba.dubbo.config.spring.beans.factory.annotation)
         * buildReferenceBean:385, ReferenceAnnotationBeanPostProcessor (com.alibaba.dubbo.config.spring.beans.factory.annotation)
         * access$100:65, ReferenceAnnotationBeanPostProcessor (com.alibaba.dubbo.config.spring.beans.factory.annotation)
         * inject:363, ReferenceAnnotationBeanPostProcessor$ReferenceFieldElement (com.alibaba.dubbo.config.spring.beans.factory.annotation)
         * inject:90, InjectionMetadata (org.springframework.beans.factory.annotation)
         * postProcessPropertyValues:92, ReferenceAnnotationBeanPostProcessor (com.alibaba.dubbo.config.spring.beans.factory.annotation)
         * populateBean:1336, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
         * doCreateBean:572, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
         * createBean:495, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
         * lambda$doGetBean$0:317, AbstractBeanFactory (org.springframework.beans.factory.support)
         * getObject:-1, 2054071421 (org.springframework.beans.factory.support.AbstractBeanFactory$$Lambda$229)
         * getSingleton:222, DefaultSingletonBeanRegistry (org.springframework.beans.factory.support)
         * doGetBean:315, AbstractBeanFactory (org.springframework.beans.factory.support)
         * getBean:199, AbstractBeanFactory (org.springframework.beans.factory.support)
         * preInstantiateSingletons:759, DefaultListableBeanFactory (org.springframework.beans.factory.support)
         * finishBeanFactoryInitialization:867, AbstractApplicationContext (org.springframework.context.support)
         * __refresh:548, AbstractApplicationContext (org.springframework.context.support)
         * jrLockAndRefresh:41002, AbstractApplicationContext (org.springframework.context.support)
         * refresh:42008, AbstractApplicationContext (org.springframework.context.support)
         * refresh:142, ServletWebServerApplicationContext (org.springframework.boot.web.servlet.context)
         * refresh:754, SpringApplication (org.springframework.boot)
         * refreshContext:386, SpringApplication (org.springframework.boot)
         * run:307, SpringApplication (org.springframework.boot)
         * run:1242, SpringApplication (org.springframework.boot)
         * run:1230, SpringApplication (org.springframework.boot)
         * main:48, YuerChatroomWebApplication (com.yupaopao.hug.chatroom.web)
         */
        consumerConfigurationListener.addNotifyListener(this);
        referenceConfigurationListener = new ReferenceConfigurationListener(url.getOrDefaultModuleModel(), this, url);
    }

    private ConsumerConfigurationListener getConsumerConfigurationListener(ModuleModel moduleModel) {
        return moduleModel.getBeanFactory().getOrRegisterBean(ConsumerConfigurationListener.class,
            type -> new ConsumerConfigurationListener(moduleModel));
    }

    @Override
    public void unSubscribe(URL url) {
        super.unSubscribe(url);
        consumerConfigurationListener.removeNotifyListener(this);
        referenceConfigurationListener.stop();
    }

    @Override
    public synchronized void notify(List<URL> urls) {
        if (isDestroyed()) {
            return;
        }

        /**
         * 对不同类别的元数据进行分类.
         * 消费端启动时，消费端会订阅providers、routers、configurators这三个目录，分别对应服务提供者、路由和动态配置变更通知。
         */
        Map<String, List<URL>> categoryUrls = urls.stream()
                .filter(Objects::nonNull)
                .filter(this::isValidCategory)
                .filter(this::isNotCompatibleFor26x)
                .collect(Collectors.groupingBy(this::judgeCategory));

        /**
         * 配置信息，比如服务降级信息
         */
        List<URL> configuratorURLs = categoryUrls.getOrDefault(CONFIGURATORS_CATEGORY, Collections.emptyList());
        this.configurators = Configurator.toConfigurators(configuratorURLs).orElse(this.configurators);

        /**
         * 路由信息收集并保存
         */
        List<URL> routerURLs = categoryUrls.getOrDefault(ROUTERS_CATEGORY, Collections.emptyList());
        toRouters(routerURLs).ifPresent(this::addRouters);

        // providers
        /**
         * 服务提供者和信息
         */
        List<URL> providerURLs = categoryUrls.getOrDefault(PROVIDERS_CATEGORY, Collections.emptyList());

        // 3.x added for extend URL address
        ExtensionLoader<AddressListener> addressListenerExtensionLoader = getUrl().getOrDefaultModuleModel().getExtensionLoader(AddressListener.class);
        List<AddressListener> supportedListeners = addressListenerExtensionLoader.getActivateExtension(getUrl(), (String[]) null);
        if (supportedListeners != null && !supportedListeners.isEmpty()) {
            for (AddressListener addressListener : supportedListeners) {
                providerURLs = addressListener.notify(providerURLs, getConsumerUrl(),this);
            }
        }
        /**
         * 从Zookeeper返回的服务提供者的信息里获取对应的路由的规则
         */
        refreshOverrideAndInvoker(providerURLs);
    }

    @Override
    public boolean isServiceDiscovery() {
        return false;
    }

    private String judgeCategory(URL url) {
        if (UrlUtils.isConfigurator(url)) {
            return CONFIGURATORS_CATEGORY;
        } else if (UrlUtils.isRoute(url)) {
            return ROUTERS_CATEGORY;
        } else if (UrlUtils.isProvider(url)) {
            return PROVIDERS_CATEGORY;
        }
        return "";
    }

    // RefreshOverrideAndInvoker will be executed by registryCenter and configCenter, so it should be synchronized.
    private synchronized void refreshOverrideAndInvoker(List<URL> urls) {
        // mock zookeeper://xxx?mock=return null
        refreshInvoker(urls);
    }

    /**
     * Convert the invokerURL list to the Invoker Map. The rules of the conversion are as follows:
     * 将InvokerURL列表转为InvokerMap 转换规则如下
     * <ol>
     * <li> If URL has been converted to invoker, it is no longer re-referenced and obtained directly from the cache,
     * and notice that any parameter changes in the URL will be re-referenced.</li>
     * 如果Url已经转换为调用者，就不再被重新引用，而是直接从缓存中获取，注意URL中的任何参数变化都会被重新引用
     * <li>If the incoming invoker list is not empty, it means that it is the latest invoker list.</li>
     * 如果传入的调用者列表不为空，则表示他是最新的调用者列表
     * <li>If the list of incoming invokerUrl is empty, It means that the rule is only a override rule or a route
     * rule, which needs to be re-contrasted to decide whether to re-reference.</li>
     * 如果传入的invokerURL列表为空，则表示该规则只是覆盖规则或路由规则，需要重新对比决定是否重新引用。
     * </ol>
     *
     * @param invokerUrls this parameter can't be null
     */
    private void refreshInvoker(List<URL> invokerUrls) {
        Assert.notNull(invokerUrls, "invokerUrls should not be null");

        /**
         * 只有一个服务提供者时
         *
         */
        if (invokerUrls.size() == 1
                && invokerUrls.get(0) != null
                && EMPTY_PROTOCOL.equals(invokerUrls.get(0).getProtocol())) {
            this.forbidden = true; // Forbid to access
            routerChain.setInvokers(BitList.emptyList());
            destroyAllInvokers(); // Close all invokers
        } else {
            /**
             * 锁哥服务提供者时
             *
             */
            this.forbidden = false; // Allow to access
            
            if (invokerUrls == Collections.<URL>emptyList()) {
                invokerUrls = new ArrayList<>();
            }
            // use local reference to avoid NPE as this.cachedInvokerUrls will be set null by destroyAllInvokers().
            Set<URL> localCachedInvokerUrls = this.cachedInvokerUrls;
            if (invokerUrls.isEmpty() && localCachedInvokerUrls != null) {
                logger.warn("Service" + serviceKey + " received empty address list with no EMPTY protocol set, trigger empty protection.");
                invokerUrls.addAll(localCachedInvokerUrls);
            } else {
                localCachedInvokerUrls = new HashSet<>();
                localCachedInvokerUrls.addAll(invokerUrls);//Cached invoker urls, convenient for comparison
                this.cachedInvokerUrls = localCachedInvokerUrls;
            }
            if (invokerUrls.isEmpty()) {
                return;
            }

            // use local reference to avoid NPE as this.urlInvokerMap will be set null concurrently at destroyAllInvokers().
            Map<URL, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap;
            // can't use local reference as oldUrlInvokerMap's mappings might be removed directly at toInvokers().
            Map<URL, Invoker<T>> oldUrlInvokerMap = null;
            if (localUrlInvokerMap != null) {
                // the initial capacity should be set greater than the maximum number of entries divided by the load factor to avoid resizing.
                oldUrlInvokerMap = new LinkedHashMap<>(Math.round(1 + localUrlInvokerMap.size() / DEFAULT_HASHMAP_LOAD_FACTOR));
                localUrlInvokerMap.forEach(oldUrlInvokerMap::put);
            }
            /**
             * 根据获取的最新的服务提供者的URL地址，将其转为具体的invoke列表，也就是说每个提供者的URL会被转换为一个Invoker对象，
             *
             */
            Map<URL, Invoker<T>> newUrlInvokerMap = toInvokers(oldUrlInvokerMap, invokerUrls);// Translate url list to Invoker map

            /**
             * If the calculation is wrong, it is not processed.
             *
             * 1. The protocol configured by the client is inconsistent with the protocol of the server.
             *    eg: consumer protocol = dubbo, provider only has other protocol services(rest).
             * 2. The registration center is not robust and pushes illegal specification data.
             *
             */
            if (CollectionUtils.isEmptyMap(newUrlInvokerMap)) {
                logger.error(new IllegalStateException("urls to invokers error .invokerUrls.size :" + invokerUrls.size() + ", invoker.size :0. urls :" + invokerUrls
                        .toString()));
                return;
            }

            List<Invoker<T>> newInvokers = Collections.unmodifiableList(new ArrayList<>(newUrlInvokerMap.values()));
            this.setInvokers(multiGroup ? new BitList<>(toMergeInvokerList(newInvokers)) : new BitList<>(newInvokers));
            // pre-route and build cache
            /**
             * 设置newInvokers到routerChain
             * RouterChain中保存了可用服务提供者对应的invokers列表和路由规则信息，当服务消费方的集群容错策略要获取
             * 可用服务提供者和对应的 invoker列表时，会调用RouterChian的route方法，其内部根据路由规则信息和invokers列表
             * 来提供服务
             *
             * routerChain链什么时候被创建？ RouterChain是在小飞度阿奴启动过程中通过RegistryProtocol的doRefer方法调用RegistryDirectory的buildRouterChain
             * 方法创建的。
             *
             */
            routerChain.setInvokers(this.getInvokers());
            this.urlInvokerMap = newUrlInvokerMap;

            try {
                /**
                 * 关闭无用的invokers
                 */
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap); // Close the unused Invoker
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }

            // notify invokers refreshed
            this.invokersChanged();
        }
    }

    private List<Invoker<T>> toMergeInvokerList(List<Invoker<T>> invokers) {
        List<Invoker<T>> mergedInvokers = new ArrayList<>();
        Map<String, List<Invoker<T>>> groupMap = new HashMap<>();
        for (Invoker<T> invoker : invokers) {
            String group = invoker.getUrl().getGroup("");
            groupMap.computeIfAbsent(group, k -> new ArrayList<>());
            groupMap.get(group).add(invoker);
        }

        if (groupMap.size() == 1) {
            mergedInvokers.addAll(groupMap.values().iterator().next());
        } else if (groupMap.size() > 1) {
            for (List<Invoker<T>> groupList : groupMap.values()) {
                StaticDirectory<T> staticDirectory = new StaticDirectory<>(groupList);
                staticDirectory.buildRouterChain();
                mergedInvokers.add(cluster.join(staticDirectory, false));
            }
        } else {
            mergedInvokers = invokers;
        }
        return mergedInvokers;
    }

    /**
     * @param urls
     * @return null : no routers ,do nothing
     * else :routers list
     */
    private Optional<List<Router>> toRouters(List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            return Optional.empty();
        }

        List<Router> routers = new ArrayList<>();
        for (URL url : urls) {
            if (EMPTY_PROTOCOL.equals(url.getProtocol())) {
                continue;
            }
            String routerType = url.getParameter(ROUTER_KEY);
            if (routerType != null && routerType.length() > 0) {
                url = url.setProtocol(routerType);
            }
            try {
                Router router = routerFactory.getRouter(url);
                if (!routers.contains(router)) {
                    routers.add(router);
                }
            } catch (Throwable t) {
                logger.error("convert router url to router error, url: " + url, t);
            }
        }

        return Optional.of(routers);
    }

    /**
     * Turn urls into invokers, and if url has been refer, will not re-reference.
     * the items that will be put into newUrlInvokeMap will be removed from oldUrlInvokerMap.
     *
     * @param oldUrlInvokerMap it might be modified during the process.
     * @param urls
     * @return invokers
     */
    private Map<URL, Invoker<T>> toInvokers(Map<URL, Invoker<T>> oldUrlInvokerMap, List<URL> urls) {
        Map<URL, Invoker<T>> newUrlInvokerMap = new ConcurrentHashMap<>(urls == null ? 1 : (int) (urls.size() / 0.75f + 1));
        if (urls == null || urls.isEmpty()) {
            return newUrlInvokerMap;
        }

        String queryProtocols = this.queryMap.get(PROTOCOL_KEY);
        for (URL providerUrl : urls) {
            // If protocol is configured at the reference side, only the matching protocol is selected
            if (queryProtocols != null && queryProtocols.length() > 0) {
                boolean accept = false;
                String[] acceptProtocols = queryProtocols.split(",");
                /**
                 *
                 * 根据消费方protocol配置过滤不匹配协议
                 *
                 */
                for (String acceptProtocol : acceptProtocols) {
                    if (providerUrl.getProtocol().equals(acceptProtocol)) {
                        accept = true;
                        break;
                    }
                }
                if (!accept) {
                    continue;
                }
            }
            if (EMPTY_PROTOCOL.equals(providerUrl.getProtocol())) {
                continue;
            }
            if (!getUrl().getOrDefaultFrameworkModel().getExtensionLoader(Protocol.class).hasExtension(providerUrl.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + providerUrl.getProtocol() +
                        " in notified url: " + providerUrl + " from registry " + getUrl().getAddress() +
                        " to consumer " + NetUtils.getLocalHost() + ", supported protocol: " +
                    getUrl().getOrDefaultFrameworkModel().getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }
            /**
             * 合并provider端配置数据，比如服务端ip和port等
             */
            URL url = mergeUrl(providerUrl);

            // Cache key is url that does not merge with consumer side parameters, regardless of how the consumer combines parameters, if the server url changes, then refer again
            Invoker<T> invoker = oldUrlInvokerMap == null ? null : oldUrlInvokerMap.remove(url);
            if (invoker == null) { // Not in the cache, refer again
                try {
                    boolean enabled = true;
                    if (url.hasParameter(DISABLED_KEY)) {
                        enabled = !url.getParameter(DISABLED_KEY, false);
                    } else {
                        enabled = url.getParameter(ENABLED_KEY, true);
                    }
                    if (enabled) {
                        /**
                         * 这里调用Dubbo协议转换服务到Invoker
                         * 这里protocol对象
                         * 在ProtocolFilterWrapper refer中触发链式构造
                         */
                        invoker = protocol.refer(serviceType, url);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + url + ")" + t.getMessage(), t);
                }
                if (invoker != null) { // Put new invoker in cache
                    newUrlInvokerMap.put(url, invoker);
                }
            } else {
                newUrlInvokerMap.put(url, invoker);
            }
        }
        return newUrlInvokerMap;
    }

    /**
     * Merge url parameters. the order is: override > -D >Consumer > Provider
     *
     * @param providerUrl
     * @return
     */
    private URL mergeUrl(URL providerUrl) {
        if (providerUrl instanceof ServiceAddressURL) {
            providerUrl = overrideWithConfigurator(providerUrl);
        } else {
            providerUrl = applicationModel.getBeanFactory().getBean(ClusterUtils.class).mergeUrl(providerUrl, queryMap); // Merge the consumer side parameters
            providerUrl = overrideWithConfigurator(providerUrl);
            providerUrl = providerUrl.addParameter(Constants.CHECK_KEY, String.valueOf(false)); // Do not check whether the connection is successful or not, always create Invoker!
        }

        // FIXME, kept for mock
        if (providerUrl.hasParameter(MOCK_KEY) || providerUrl.getAnyMethodParameter(MOCK_KEY) != null) {
            providerUrl = providerUrl.removeParameter(TAG_KEY);
        }

        if ((providerUrl.getPath() == null || providerUrl.getPath()
                .length() == 0) && DUBBO_PROTOCOL.equals(providerUrl.getProtocol())) { // Compatible version 1.0
            //fix by tony.chenl DUBBO-44
            String path = directoryUrl.getServiceInterface();
            if (path != null) {
                int i = path.indexOf('/');
                if (i >= 0) {
                    path = path.substring(i + 1);
                }
                i = path.lastIndexOf(':');
                if (i >= 0) {
                    path = path.substring(0, i);
                }
                providerUrl = providerUrl.setPath(path);
            }
        }
        return providerUrl;
    }

    private URL overrideWithConfigurator(URL providerUrl) {
        // override url with configurator from "override://" URL for dubbo 2.6 and before
        providerUrl = overrideWithConfigurators(this.configurators, providerUrl);

        // override url with configurator from "app-name.configurators"
        providerUrl = overrideWithConfigurators(consumerConfigurationListener.getConfigurators(), providerUrl);

        // override url with configurator from configurators from "service-name.configurators"
        if (referenceConfigurationListener != null) {
            providerUrl = overrideWithConfigurators(referenceConfigurationListener.getConfigurators(), providerUrl);
        }

        return providerUrl;
    }

    private URL overrideWithConfigurators(List<Configurator> configurators, URL url) {
        if (CollectionUtils.isNotEmpty(configurators)) {
            if (url instanceof DubboServiceAddressURL) {
                DubboServiceAddressURL interfaceAddressURL = (DubboServiceAddressURL) url;
                URL overriddenURL = interfaceAddressURL.getOverrideURL();
                if (overriddenURL == null) {
                    String appName = interfaceAddressURL.getApplication();
                    String side = interfaceAddressURL.getSide();
                    overriddenURL = URLBuilder.from(interfaceAddressURL)
                            .clearParameters()
                            .addParameter(APPLICATION_KEY, appName)
                            .addParameter(SIDE_KEY, side).build();
                }
                for (Configurator configurator : configurators) {
                    overriddenURL = configurator.configure(overriddenURL);
                }
                url = new DubboServiceAddressURL(
                        interfaceAddressURL.getUrlAddress(),
                        interfaceAddressURL.getUrlParam(),
                        interfaceAddressURL.getConsumerURL(),
                        (ServiceConfigURL) overriddenURL);
            } else {
                for (Configurator configurator : configurators) {
                    url = configurator.configure(url);
                }
            }
        }
        return url;
    }

    /**
     * Close all invokers
     */
    @Override
    protected void destroyAllInvokers() {
        Map<URL, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
        if (!CollectionUtils.isEmptyMap(localUrlInvokerMap)) {
            for (Invoker<T> invoker : new ArrayList<>(localUrlInvokerMap.values())) {
                try {
                    invoker.destroyAll();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }
            localUrlInvokerMap.clear();
        }

        this.urlInvokerMap = null;
        this.cachedInvokerUrls = null;
        destroyInvokers();
    }

    private void destroyUnusedInvokers(Map<URL, Invoker<T>> oldUrlInvokerMap, Map<URL, Invoker<T>> newUrlInvokerMap) {
        if (CollectionUtils.isEmptyMap(newUrlInvokerMap)) {
            destroyAllInvokers();
            return;
        }

        if (CollectionUtils.isEmptyMap(oldUrlInvokerMap)) {
            return;
        }

        for (Map.Entry<URL, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {
            Invoker<T> invoker = entry.getValue();
            if (invoker != null) {
                try {
                    invoker.destroyAll();
                    if (logger.isDebugEnabled()) {
                        logger.debug("destroy invoker[" + invoker.getUrl() + "] success. ");
                    }
                } catch (Exception e) {
                    logger.warn("destroy invoker[" + invoker.getUrl() + "] failed. " + e.getMessage(), e);
                }
            }
        }

        logger.info("New url total size, " + newUrlInvokerMap.size() + ", destroyed total size " + oldUrlInvokerMap.size());
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<URL, Invoker<T>> getUrlInvokerMap() {
        return urlInvokerMap;
    }

    private boolean isValidCategory(URL url) {
        String category = url.getCategory(DEFAULT_CATEGORY);
        if ((ROUTERS_CATEGORY.equals(category) || ROUTE_PROTOCOL.equals(url.getProtocol())) ||
                PROVIDERS_CATEGORY.equals(category) ||
                CONFIGURATORS_CATEGORY.equals(category) || DYNAMIC_CONFIGURATORS_CATEGORY.equals(category) ||
                APP_DYNAMIC_CONFIGURATORS_CATEGORY.equals(category)) {
            return true;
        }
        logger.warn("Unsupported category " + category + " in notified url: " + url + " from registry " +
                getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost());
        return false;
    }

    private boolean isNotCompatibleFor26x(URL url) {
        return StringUtils.isEmpty(url.getParameter(COMPATIBLE_CONFIG_KEY));
    }

    private static class ReferenceConfigurationListener extends AbstractConfiguratorListener {
        private RegistryDirectory directory;
        private URL url;

        ReferenceConfigurationListener(ModuleModel moduleModel, RegistryDirectory directory, URL url) {
            super(moduleModel);
            this.directory = directory;
            this.url = url;
            this.initWith(DynamicConfiguration.getRuleKey(url) + CONFIGURATORS_SUFFIX);
        }

        void stop() {
            this.stopListen(DynamicConfiguration.getRuleKey(url) + CONFIGURATORS_SUFFIX);
        }

        @Override
        protected void notifyOverrides() {
            // to notify configurator/router changes
            directory.refreshOverrideAndInvoker(Collections.emptyList());
        }
    }

    private static class ConsumerConfigurationListener extends AbstractConfiguratorListener {
        List<RegistryDirectory> listeners = new ArrayList<>();

        ConsumerConfigurationListener(ModuleModel moduleModel) {
            super(moduleModel);
            this.initWith(moduleModel.getApplicationModel().getApplicationName() + CONFIGURATORS_SUFFIX);
        }

        void addNotifyListener(RegistryDirectory listener) {
            this.listeners.add(listener);
        }

        void removeNotifyListener(RegistryDirectory listener) {
            this.listeners.remove(listener);
        }

        @Override
        protected void notifyOverrides() {
            listeners.forEach(listener -> listener.refreshOverrideAndInvoker(Collections.emptyList()));
        }
    }

}

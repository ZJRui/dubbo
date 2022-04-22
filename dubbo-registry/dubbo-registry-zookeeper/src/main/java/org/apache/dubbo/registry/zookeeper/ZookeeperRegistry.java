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
package org.apache.dubbo.registry.zookeeper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.RegistryNotifier;
import org.apache.dubbo.registry.support.CacheableFailbackRegistry;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;
import org.apache.dubbo.rpc.RpcException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.CHECK_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONSUMERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;

/**
 * ZookeeperRegistry
 */
public class ZookeeperRegistry extends CacheableFailbackRegistry {

    private final static Logger logger = LoggerFactory.getLogger(ZookeeperRegistry.class);

    private final static String DEFAULT_ROOT = "dubbo";

    private final String root;

    private final Set<String> anyServices = new ConcurrentHashSet<>();

    private final ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners = new ConcurrentHashMap<>();

    private ZookeeperClient zkClient;

    public ZookeeperRegistry(URL url, ZookeeperTransporter zookeeperTransporter) {
        super(url);
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        String group = url.getGroup(DEFAULT_ROOT);
        if (!group.startsWith(PATH_SEPARATOR)) {
            group = PATH_SEPARATOR + group;
        }
        this.root = group;
        zkClient = zookeeperTransporter.connect(url);
        zkClient.addStateListener((state) -> {
            if (state == StateListener.RECONNECTED) {
                logger.warn("Trying to fetch the latest urls, in case there're provider changes during connection loss.\n" +
                    " Since ephemeral ZNode will not get deleted for a connection lose, " +
                    "there's no need to re-register url of this instance.");
                ZookeeperRegistry.this.fetchLatestAddresses();
            } else if (state == StateListener.NEW_SESSION_CREATED) {
                logger.warn("Trying to re-register urls and re-subscribe listeners of this instance to registry...");
                try {
                    ZookeeperRegistry.this.recover();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            } else if (state == StateListener.SESSION_LOST) {
                logger.warn("Url of this instance will be deleted from registry soon. " +
                    "Dubbo client will try to re-register once a new session is created.");
            } else if (state == StateListener.SUSPENDED) {

            } else if (state == StateListener.CONNECTED) {

            }
        });
    }

    @Override
    public boolean isAvailable() {
        return zkClient != null && zkClient.isConnected();
    }

    @Override
    public void destroy() {
        super.destroy();
        // Just release zkClient reference, but can not close zk client here for zk client is shared somewhere else.
        // See org.apache.dubbo.remoting.zookeeper.AbstractZookeeperTransporter#destroy()
        zkClient = null;
    }

    private void checkDestroyed() {
        if (zkClient == null) {
            throw new IllegalStateException("registry is destroyed");
        }
    }

    @Override
    public void doRegister(URL url) {
        try {
            checkDestroyed();
            zkClient.create(toUrlPath(url), url.getParameter(DYNAMIC_KEY, true));
        } catch (Throwable e) {
            throw new RpcException("Failed to register " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doUnregister(URL url) {
        try {
            checkDestroyed();
            zkClient.delete(toUrlPath(url));
        } catch (Throwable e) {
            throw new RpcException("Failed to unregister " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doSubscribe(final URL url, final NotifyListener listener) {
        try {
            checkDestroyed();
            /**
             * 订阅所有数据
             */
            if (ANY_VALUE.equals(url.getServiceInterface())) {
                String root = toRootPath();
                boolean check = url.getParameter(CHECK_KEY, false);
                ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.computeIfAbsent(url, k -> new ConcurrentHashMap<>());
                /**
                 * zkListener 为空，说明是第一次，新建一个listener
                 */
                ChildListener zkListener = listeners.computeIfAbsent(listener, k -> (parentPath, currentChilds) -> {
                    /**
                     * 内部类的方法，不会立即执行，只会才出发变更通知时执行
                     */
                    for (String child : currentChilds) {
                        child = URL.decode(child);
                        /***
                         * 如果存在子节点还未被订阅，说明是新的节点，则订阅
                         */
                        if (!anyServices.contains(child)) {
                            anyServices.add(child);
                            subscribe(url.setPath(child).addParameters(INTERFACE_KEY, child,
                                Constants.CHECK_KEY, String.valueOf(check)), k);
                        }
                    }
                });
                /**
                 * 创建持久节点，接下来订阅持久节点的直接子节点
                 */
                zkClient.create(root, false);
                List<String> services = zkClient.addChildListener(root, zkListener);
                if (CollectionUtils.isNotEmpty(services)) {
                    /**
                     * 遍历所有子节点进行订阅
                     */
                    for (String service : services) {
                        service = URL.decode(service);
                        anyServices.add(service);
                        /**
                         * 增加当前节点的订阅，并且会返回该节点下所有子节点列表
                         */
                        subscribe(url.setPath(service).addParameters(INTERFACE_KEY, service,
                            Constants.CHECK_KEY, String.valueOf(check)), listener);
                    }
                }
            } else {
                CountDownLatch latch = new CountDownLatch(1);
                try {
                    List<URL> urls = new ArrayList<>();
                    /**
                     * 根据URL的类别获取一组要订阅的路径。
                     *
                     * 此处会根据url中的category属性值获取具体的类别： providers,routers,consumers,configurators,然后拉取直接子节点的数据进行通知notify。
                     * 如果是providers类别的数据，则订阅方会更新本地Directory管理的Invoker服务列表；
                     * 如果是routers分类，则订阅方会更新本地路由规则列表
                     * 如果是configurators类别，则订阅方会更新或覆盖本地动态参数列表。
                     *
                     *
                     */
                    for (String path : toCategoriesPath(url)) {
                        /**
                         * 如果listener为空则创建缓存
                         */
                        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.computeIfAbsent(url, k -> new ConcurrentHashMap<>());

                        /**
                         *
                         */

                        ChildListener zkListener = listeners.computeIfAbsent(listener, k -> new RegistryChildListenerImpl(url, path, k, latch));
                        if (zkListener instanceof RegistryChildListenerImpl) {
                            ((RegistryChildListenerImpl) zkListener).setLatch(latch);
                        }
                        /**
                         * 订阅该节点下的子路径并缓存
                         */
                        zkClient.create(path, false);
                        /**
                         *
                         * 添加Zookeeper事件监听
                         */
                        List<String> children = zkClient.addChildListener(path, zkListener);
                        if (children != null) {
                            urls.addAll(toUrlsWithEmpty(url, path, children));
                        }
                    }
                    /**
                     *
                     * 上面从ZooKeeper获取服务提供者的地址列表，等Zookeeper返回地址列表后会调用RegistryDirectory的notify方法
                     * 这里的listener就是 RegistryDirectory ,RegistryDirectory 继承自DynamicDirectory
                     * DynamicDirectory 实现了NotifyListener 接口。
                     *
                     * 在第一次发起订阅时会进行一次数据拉取操作，同时触发RegistryDirectory.notify方法，这里的通知数据是某一个类别的全量数据，
                     *   比如Providers和routes类别数据。当通知Providers数据时，在RegistryDirectory#toInvokers方法内完成Invoker转换。
                     *
                     *   ==========================
                     *  zookeeper的订阅通常有 pull和push 两种方式，一种是客户端定时轮询注册中心拉取配置，另一种是注册中煤新主动推送数据给客户端。 这两种方式各有利弊，目前dubbo采用
                     *  的是第一次启动拉取方式，后续接收事件重新拉取数据。 也就是事件通知+客户端拉取。  在上面 客户端第一次连接上注册中心时，拉获取对应目录下的全量数据，
                     *  并在订阅节点上注册一个watch，客户端与注册中心之间保持TCP长连接，后续每个节点有任何数据变化的时候注册中心会根据watcher的回调主动通知客户端，
                     *  然后客户端收到通知后，会把对应节点下的全量数据都拉取过来。这一点在notifyListener的notify接口上有约束的注释说明。
                     *
                     * ---------------
                     * 回调notifyListener，更新本地缓存信息
                     *
                     */
                    notify(url, listener, urls);
                } finally {
                    // tells the listener to run only after the sync notification of main thread finishes.
                    latch.countDown();
                }
            }
        } catch (Throwable e) {
            throw new RpcException("Failed to subscribe " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
        checkDestroyed();
        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
        if (listeners != null) {
            ChildListener zkListener = listeners.remove(listener);
            if (zkListener != null) {
                if (ANY_VALUE.equals(url.getServiceInterface())) {
                    String root = toRootPath();
                    zkClient.removeChildListener(root, zkListener);
                } else {
                    for (String path : toCategoriesPath(url)) {
                        zkClient.removeChildListener(path, zkListener);
                    }
                }
            }

            if (listeners.isEmpty()) {
                zkListeners.remove(url);
            }
        }
    }

    @Override
    public List<URL> lookup(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("lookup url == null");
        }
        try {
            checkDestroyed();
            List<String> providers = new ArrayList<>();
            for (String path : toCategoriesPath(url)) {
                List<String> children = zkClient.getChildren(path);
                if (children != null) {
                    providers.addAll(children);
                }
            }
            return toUrlsWithoutEmpty(url, providers);
        } catch (Throwable e) {
            throw new RpcException("Failed to lookup " + url + " from zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    private String toRootDir() {
        if (root.equals(PATH_SEPARATOR)) {
            return root;
        }
        return root + PATH_SEPARATOR;
    }

    private String toRootPath() {
        return root;
    }

    private String toServicePath(URL url) {
        String name = url.getServiceInterface();
        if (ANY_VALUE.equals(name)) {
            return toRootPath();
        }
        return toRootDir() + URL.encode(name);
    }

    private String[] toCategoriesPath(URL url) {
        String[] categories;
        if (ANY_VALUE.equals(url.getCategory())) {
            categories = new String[]{PROVIDERS_CATEGORY, CONSUMERS_CATEGORY, ROUTERS_CATEGORY, CONFIGURATORS_CATEGORY};
        } else {
            categories = url.getCategory(new String[]{DEFAULT_CATEGORY});
        }
        String[] paths = new String[categories.length];
        for (int i = 0; i < categories.length; i++) {
            paths[i] = toServicePath(url) + PATH_SEPARATOR + categories[i];
        }
        return paths;
    }

    private String toCategoryPath(URL url) {
        return toServicePath(url) + PATH_SEPARATOR + url.getCategory(DEFAULT_CATEGORY);
    }

    private String toUrlPath(URL url) {
        return toCategoryPath(url) + PATH_SEPARATOR + URL.encode(url.toFullString());
    }

    /**
     * When zookeeper connection recovered from a connection loss, it need to fetch the latest provider list.
     * re-register watcher is only a side effect and is not mandate.
     */
    private void fetchLatestAddresses() {
        // subscribe
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Fetching the latest urls of " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    removeFailedSubscribed(url, listener);
                    addFailedSubscribed(url, listener);
                }
            }
        }
    }

    @Override
    protected boolean isMatch(URL subscribeUrl, URL providerUrl) {
        return UrlUtils.isMatch(subscribeUrl, providerUrl);
    }

    private class RegistryChildListenerImpl implements ChildListener {
        private RegistryNotifier notifier;
        private long lastExecuteTime;
        private volatile CountDownLatch latch;

        public RegistryChildListenerImpl(URL consumerUrl, String path, NotifyListener listener, CountDownLatch latch) {
            this.latch = latch;
            notifier = new RegistryNotifier(getUrl(), ZookeeperRegistry.this.getDelay()) {
                @Override
                public void notify(Object rawAddresses) {
                    long delayTime = getDelayTime();
                    if (delayTime <= 0) {
                        this.doNotify(rawAddresses);
                    } else {
                        long interval = delayTime - (System.currentTimeMillis() - lastExecuteTime);
                        if (interval > 0) {
                            try {
                                Thread.sleep(interval);
                            } catch (InterruptedException e) {
                                // ignore
                            }
                        }
                        lastExecuteTime = System.currentTimeMillis();
                        this.doNotify(rawAddresses);
                    }
                }

                @Override
                protected void doNotify(Object rawAddresses) {
                    ZookeeperRegistry.this.notify(consumerUrl, listener, ZookeeperRegistry.this.toUrlsWithEmpty(consumerUrl, path, (List<String>) rawAddresses));
                }
            };
        }

        public void setLatch(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void childChanged(String path, List<String> children) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                logger.warn("Zookeeper children listener thread was interrupted unexpectedly, may cause race condition with the main thread.");
            }
            notifier.notify(children);
        }
    }
}

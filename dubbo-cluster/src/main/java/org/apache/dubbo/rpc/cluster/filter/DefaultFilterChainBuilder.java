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
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.extension.ExtensionDirector;
import org.apache.dubbo.common.extension.support.MultiInstanceActivateComparator;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.cluster.ClusterInvoker;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Activate
public class DefaultFilterChainBuilder implements FilterChainBuilder {

    /**
     * build consumer/provider filter chain
     */
    @Override
    public <T> Invoker<T> buildInvokerChain(final Invoker<T> originalInvoker, String key, String group) {

        Invoker<T> last = originalInvoker;
        URL url = originalInvoker.getUrl();
        List<ModuleModel> moduleModels = getModuleModelsFromUrl(url);
        List<Filter> filters;
        /**
         *
         * ScopeModelUtil.getExtensionLoader(Filter.class, moduleModels.get(0)).getActivateExtension(url, key, group);
         * 获取所有激活的Filter，然后使用链表方式形成责任链。
         *
         */
        if (moduleModels != null && moduleModels.size() == 1) {
            filters = ScopeModelUtil.getExtensionLoader(Filter.class, moduleModels.get(0)).getActivateExtension(url, key, group);
        } else if (moduleModels != null && moduleModels.size() > 1) {
            filters = new ArrayList<>();
            List<ExtensionDirector> directors = new ArrayList<>();
            for (ModuleModel moduleModel : moduleModels) {
                List<Filter> tempFilters = ScopeModelUtil.getExtensionLoader(Filter.class, moduleModel).getActivateExtension(url, key, group);
                filters.addAll(tempFilters);
                directors.add(moduleModel.getExtensionDirector());
            }
            filters = sortingAndDeduplication(filters, directors);

        } else {
            filters = ScopeModelUtil.getExtensionLoader(Filter.class, null).getActivateExtension(url, key, group);
        }


        if (!CollectionUtils.isEmpty(filters)) {
            /**
             * 假如我们有四个 ABCD Filter，
             *
             * 首先取出DFilter，此时last=originalInvoker ，这个OriginInvoker就是DubboProtocol中返回的DubboInvoker
             * 然后创建一个CopyOfFilterChainNode 这个Node本质上是一个Invoker。
             * 这个Invoker的next属性是DubboProtocol，Filter属性是D
             *
             * CopyOfFilterChainNode 这个Invoker内部的invoke方法本质上是执行了 Filter的 invoke方法。
             *
             * 然后 为 C Filter创建一个 CopyOfFilterChainNode ，这个Node的next 是  D_CopyOfFilterChainNode,filter是CFilter
             *
             *
             */
            for (int i = filters.size() - 1; i >= 0; i--) {
                final Filter filter = filters.get(i);
                final Invoker<T> next = last;
                last = new CopyOfFilterChainNode<>(originalInvoker, next, filter);
            }
            /**
             * 这里创建了一个调用链对象CallbackRegistrationInvoker，这个调用链对象持有  last=A_CopyOfFilterChainNode.
             * 在这个A_CopyOfFilterChainNode对象中，他的next属性是B_CopyOfFilterChainNode.,他的filter属性是 AFilter
             *
             * 在A_CopyOfFilterChainNode的invoke方法中 会执行AFilter的invoker方法 filter.invoke(nextNode, invocation);
             * 传递nextNode是B_CopyOfFilterChainNode
             * AFilter 的invoke方法内部会 使用接收到的 B_CopyOfFilterChainNode 执行其invoke方法。
             * B_CopyOfFilterChainNode 的invoke内部会执行 Bfilter.invoke(nextNode,invocation)
             * 这里传递的nextNode就是C_CopyOfFilterChianNode
             * 从而时间 触发整个调用链的执行。
             * 在D_CopyOfFilterChainNode内部 持有的NextNode就是DubboInvoker，Filter就是DFilter。
             * 因此在D_CopyOfFilterChainNode的invoke方法中会执行DFIlter.invoke(dubbInvoker,invocation)
             * 在DFilter内部最终会执行dubboInvoker的invoke方法。
             *
             * 我们会发现 Filter的invoke方法 在执行完Filter的逻辑的最后总是会执行 filter的invoke方法接收到的第一个参数Invoker的invoke方法
             * 这个第一个参数Invoker就是 CopyOfFilterChainNode对象。
             *
             *
             */
            return new CallbackRegistrationInvoker<>(last, filters);
        }

        return last;
    }

    /**
     * build consumer cluster filter chain
     */
    @Override
    public <T> ClusterInvoker<T> buildClusterInvokerChain(final ClusterInvoker<T> originalInvoker, String key, String group) {
        ClusterInvoker<T> last = originalInvoker;
        URL url = originalInvoker.getUrl();
        List<ModuleModel> moduleModels = getModuleModelsFromUrl(url);
        List<ClusterFilter> filters;
        if (moduleModels != null && moduleModels.size() == 1) {
            filters = ScopeModelUtil.getExtensionLoader(ClusterFilter.class, moduleModels.get(0)).getActivateExtension(url, key, group);
        } else if (moduleModels != null && moduleModels.size() > 1) {
            filters = new ArrayList<>();
            List<ExtensionDirector> directors = new ArrayList<>();
            for (ModuleModel moduleModel : moduleModels) {
                List<ClusterFilter> tempFilters = ScopeModelUtil.getExtensionLoader(ClusterFilter.class, moduleModel).getActivateExtension(url, key, group);
                filters.addAll(tempFilters);
                directors.add(moduleModel.getExtensionDirector());
            }
            filters = sortingAndDeduplication(filters, directors);

        } else {
            filters = ScopeModelUtil.getExtensionLoader(ClusterFilter.class, null).getActivateExtension(url, key, group);
        }

        if (!CollectionUtils.isEmpty(filters)) {
            for (int i = filters.size() - 1; i >= 0; i--) {
                final ClusterFilter filter = filters.get(i);
                final Invoker<T> next = last;
                last = new CopyOfClusterFilterChainNode<>(originalInvoker, next, filter);
            }
            return new ClusterCallbackRegistrationInvoker<>(originalInvoker, last, filters);
        }

        return last;
    }

    private <T> List<T> sortingAndDeduplication(List<T> filters, List<ExtensionDirector> directors) {
        Map<Class<?>, T> filtersSet = new TreeMap<>(new MultiInstanceActivateComparator(directors));
        for (T filter : filters) {
            filtersSet.putIfAbsent(filter.getClass(), filter);
        }
        return new ArrayList<>(filtersSet.values());
    }

    /**
     * When the application-level service registration and discovery strategy is adopted, the URL will be of type InstanceAddressURL,
     * and InstanceAddressURL belongs to the application layer and holds the ApplicationModel,
     * but the filter is at the module layer and holds the ModuleModel,
     * so it needs to be based on the url in the ScopeModel type to parse out all the moduleModels held by the url
     * to obtain the filter configuration.
     *
     * @param url URL
     * @return All ModuleModels in the url
     */
    private List<ModuleModel> getModuleModelsFromUrl(URL url) {
        List<ModuleModel> moduleModels = null;
        ScopeModel scopeModel = url.getScopeModel();
        if (scopeModel instanceof ApplicationModel) {
            moduleModels = ((ApplicationModel) scopeModel).getPubModuleModels();
        } else if (scopeModel instanceof ModuleModel) {
            moduleModels = new ArrayList<>();
            moduleModels.add((ModuleModel) scopeModel);
        }
        return moduleModels;
    }

}

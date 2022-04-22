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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.TimeoutCountDown;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TAG_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_ATTACHMENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIME_COUNTDOWN_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.Constants.ASYNC_KEY;
import static org.apache.dubbo.rpc.Constants.FORCE_USE_TAG;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;


/**
 * ContextFilter set the provider RpcContext with invoker, invocation, local port it is using and host for
 * current execution thread.
 *
 * @see RpcContext
 */
@Activate(group = PROVIDER, order = -10000)
public class ContextFilter implements Filter, Filter.Listener {

    private static final Set<String> UNLOADING_KEYS;

    static {
        UNLOADING_KEYS = new HashSet<>(16);
        UNLOADING_KEYS.add(PATH_KEY);
        UNLOADING_KEYS.add(INTERFACE_KEY);
        UNLOADING_KEYS.add(GROUP_KEY);
        UNLOADING_KEYS.add(VERSION_KEY);
        UNLOADING_KEYS.add(DUBBO_VERSION_KEY);
        UNLOADING_KEYS.add(TOKEN_KEY);
        UNLOADING_KEYS.add(TIMEOUT_KEY);
        UNLOADING_KEYS.add(TIMEOUT_ATTACHMENT_KEY);

        // Remove async property to avoid being passed to the following invoke chain.
        UNLOADING_KEYS.add(ASYNC_KEY);
        UNLOADING_KEYS.add(TAG_KEY);
        UNLOADING_KEYS.add(FORCE_USE_TAG);
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        /**
         * ContextFilter主要记录每个请求的调用上下文。每个调用都有可能产生很多中间临时信息，
         * 我们不可能要求在每个接口上都加一个上下文的参数，然后一路往下传。通常做法都是放在
         * ThreadLocal中，作为一个全局参数，当前线程中的任何一个地方都可以直接读/写上下文信息。
         * ContextFilter就是统一在过滤器中处理请求的上下文信息，它为每个请求维护一个
         * RpcContext对象，该对象中维护两个InternalThreadLocal (它是优化过的ThreadLocal,具体
         * 可以搜索Netty的InternalThreadLocal做了什么优化)，分别记录local和server的上下文。每次
         * 收到或发起RPC调用的时候，上下文信息都会发生改变。例如：A调用B, B调用C。当A调
         * 用B且B还未调用C时，RpcContext中保存A调用B的上下文；当B开始调用C的时候，
         * RpcContext中保存B调用C的上下文。发起调用时候的上下文是由ConsumerContextFilter实现
         * 的，这个是消费者端的过滤器，因此不在本节讲解oContextFilter保存的是收到的请求的上下文。
         *
         * ContextFilter的主要逻辑如下：
         * (1)  清除异步属性。防止异步属性传到过滤器链的下一个环节。
         * (2)  设置当前请求的上下文，如Invoker信息、地址信息、端口信息等。如果前面的过滤
         * 器已经对上下文设置了一些附件信息(attachments是一个Map,里面可以保存各种key-value
         * 数据)，则和Invoker的附件信息合并。
         * (3)  调用过滤器链的下一个节点。
         * (4)  清除上下文信息。对于异步调用的场景，即使是同一个线程，处理不同的请求也会创
         * 建一个新的RpcContext对象。因此调用完成后，需要清理对应的上下文信息。
         *
         *
         *
         * ---------------------
         * 服务提供方使用contextFilter 对请求进行拦截，并从RpcInvocation中获取attachments中的键值对，然后使用
         * RpcContext.getContext().setAttachment 设置到上下文对象中。
         *
         * invocation中的属性是在AbstractInvoker对象的invoke方法中被设置到了 invocation中的
         *
         * 从invocation对象获取附加属性map
         *
         */
        Map<String, Object> attachments = invocation.getObjectAttachments();
        /**
         *
         * 如果不为null则设置到上下文对象中。
         *
         */
        if (attachments != null) {
            Map<String, Object> newAttach = new HashMap<>(attachments.size());
            for (Map.Entry<String, Object> entry : attachments.entrySet()) {
                String key = entry.getKey();
                if (!UNLOADING_KEYS.contains(key)) {
                    newAttach.put(key, entry.getValue());
                }
            }
            attachments = newAttach;
        }

        RpcContext.getServiceContext().setInvoker(invoker)
                .setInvocation(invocation);

        RpcContext context = RpcContext.getServerAttachment();
//                .setAttachments(attachments)  // merged from dubbox
        context.setLocalAddress(invoker.getUrl().getHost(), invoker.getUrl().getPort());

        String remoteApplication = invocation.getAttachment(REMOTE_APPLICATION_KEY);
        if (StringUtils.isNotEmpty(remoteApplication)) {
            RpcContext.getServiceContext().setRemoteApplicationName(remoteApplication);
        } else {
            RpcContext.getServiceContext().setRemoteApplicationName(context.getAttachment(REMOTE_APPLICATION_KEY));
        }

        long timeout = RpcUtils.getTimeout(invocation, -1);
        if (timeout != -1) {
            // pass to next hop
            RpcContext.getClientAttachment().setObjectAttachment(TIME_COUNTDOWN_KEY, TimeoutCountDown.newCountDown(timeout, TimeUnit.MILLISECONDS));
        }

        // merged from dubbox
        // we may already add some attachments into RpcContext before this filter (e.g. in rest protocol)
        /**
         * 将附加属性设置到context中
         * 然后服务提供者就可以使用 RpcContext.getContext.getAttachment
         */
        if (attachments != null) {
            if (context.getObjectAttachments().size() > 0) {
                context.getObjectAttachments().putAll(attachments);
            } else {
                context.setObjectAttachments(attachments);
            }
        }

        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(invoker);
        }

        try {
            context.clearAfterEachInvoke(false);
            return invoker.invoke(invocation);
        } finally {
            context.clearAfterEachInvoke(true);
        }
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        // pass attachments to result
        appResponse.addObjectAttachments(RpcContext.getServerContext().getObjectAttachments());
        /**
         * 清除 attachment
         */
        removeContext();
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        removeContext();
    }

    private void removeContext() {
        RpcContext.removeServerAttachment();
        RpcContext.removeClientAttachment();
        RpcContext.removeServiceContext();
        RpcContext.removeServerContext();
    }
}

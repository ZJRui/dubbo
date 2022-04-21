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
package org.apache.dubbo.rpc.proxy;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.Constants;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.ConsumerModel;
import org.apache.dubbo.rpc.model.ServiceModel;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * InvokerHandler
 */
public class InvokerInvocationHandler implements InvocationHandler {
    private static final Logger logger = LoggerFactory.getLogger(InvokerInvocationHandler.class);
    private final Invoker<?> invoker;
    private ServiceModel serviceModel;
    private URL url;
    private String protocolServiceKey;

    public InvokerInvocationHandler(Invoker<?> handler) {
        this.invoker = handler;
        this.url = invoker.getUrl();
        this.protocolServiceKey = this.url.getProtocolServiceKey();
        this.serviceModel = this.url.getServiceModel();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

        /**
         * InvokerInvocationHandler 为具体拦截器
         * 在ReferenceConfig的createProxy方法中，首先 会通过createInvokerForRemote创建一个invoker
         * 然后使用proxyFactory.getProxy(invoker, ProtocolUtils.isGeneric(generic)); 为这个invoker创建一个代理对象。
         *
         * getProxy方法中的第二个参数是业务接口class，然后创建一个这个接口的代理实现类，然后指定InvokerInvocationHandler作为拦截器
         * 然后getProxy的第一个参数invoker为真实被代理的原始目标对象。
         * 当执行代理对象的接口方法的时候 会被InvokerInvocationHandler拦截，从而执行InvocationHandler的 invoke方法。
         * 在invoke方法中 将调用的方法等信息封装成一个Invocation，然后执行 Dubbo的Invoker对象的invoke方法。
         * 最终执行DubboInvoker的invoke 将这个调用信息发送给服务提供者。
         *
         * =========================
         * Dubbo服务消费端一次远程调用过程时序
         *
         * InvokerInvocationHandler.invoker--->MockClusterInvoker.invoke----->FailoverClusterInvoker.doInvoke.select.doSelect.invoke
         * ------> InvokeDelegete.invoke ---->ProtocolFilterWrapper.invoke---> 进入Filter责任链  ActiveLimitFilter.invoke--->dubboInvoker.doInver
         *
         *
         *
         */

        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(invoker, args);
        }
        String methodName = method.getName();
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 0) {
            if ("toString".equals(methodName)) {
                return invoker.toString();
            } else if ("$destroy".equals(methodName)) {
                invoker.destroy();
                return null;
            } else if ("hashCode".equals(methodName)) {
                return invoker.hashCode();
            }
        } else if (parameterTypes.length == 1 && "equals".equals(methodName)) {
            return invoker.equals(args[0]);
        }
        RpcInvocation rpcInvocation = new RpcInvocation(serviceModel, method, invoker.getInterface().getName(), protocolServiceKey, args);

        if (serviceModel instanceof ConsumerModel) {
            rpcInvocation.put(Constants.CONSUMER_MODEL, serviceModel);
            rpcInvocation.put(Constants.METHOD_MODEL, ((ConsumerModel) serviceModel).getMethodModel(method));
        }
        return InvocationUtil.invoke(invoker, rpcInvocation);
    }
}

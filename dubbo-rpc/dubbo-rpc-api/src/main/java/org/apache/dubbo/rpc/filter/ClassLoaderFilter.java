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

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.BaseFilter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import static org.apache.dubbo.common.constants.CommonConstants.STAGED_CLASSLOADER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.WORKING_CLASSLOADER_KEY;

/**
 * Set the current execution thread class loader to service interface's class loader.
 */
@Activate(group = CommonConstants.PROVIDER, order = -30000)
public class ClassLoaderFilter implements Filter, BaseFilter.Listener {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        /**
         * 如果读者对Java的类加载机制不清楚的话，那么会感觉ClassLoaderFilter过滤器不好理解。
         * ClassLoaderFilter主要的工作是：切换当前工作线程的类加载器到接口的类加载器，以便和接口
         * 的类加载器的上下文一起工作。
         *
         * 。我们先了解一下双亲委派模型在框架中会有哪些问题,在 ClassA 中通过反射调用了 ClassB.
         * 如果ClassA和ClassB都是同一个类加载器加载的，则它们之间是可以互相访问的，ClassA
         * 的调用会输出ClassBo但是，如果ClassA和ClassB是同一层级不同的类加载器加载的呢？
         * 假设ClassA由ClassLoaderA加载，ClassB由ClassLoaderB加载，此时ClassA是无法访问
         * ClassB的。我们按照双亲委派模型来获取一下ClassB：首先，ClassA会从ClassLoaderA中查找
         * ClassB,看是否已经加载。没找到则继续往父类加载器ParentClassLoader查找ClassB,没找到
         * 则继续往父类加载器查找 最终还没找到，会抛出ClassNotFoundException异常。
         *
         * 讲了这么多，和ClassLoaderFilter有什么关系呢？如果要实现违反双亲委派模型来查找
         * Class,那么通常会使用上下文类加载器(ContextClassLoader)。当前框架线程的类加载器可能
         * 和Invoker接口的类加载器不是同一个，而当前框架线程中又需要获取Invoker的类加载器中的
         * 一些 Class,为 了避免出现 ClassNot Found Exception,此时只需要使用 Thread. currentThread().
         * getContextClassLoader()就可以获取Invoker的类加载器，进而获得这个类加载器中的Classo
         *
         * 常见的使用例子有DubboProtocol#optimizeSerialization方法，会根据Invoker中配置的
         * optimizer参数获取扩展的自定义序列化处理类，这些外部引入的序列化类在框架的类加载器
         * 中肯定没有，因此需要使用Invoker的类加载器获取对应的类。
         *
         *
         */
        ClassLoader stagedClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader effectiveClassLoader;
        if (invocation.getServiceModel() != null) {
            effectiveClassLoader = invocation.getServiceModel().getClassLoader();
        } else {
            effectiveClassLoader = invoker.getClass().getClassLoader();
        }

        if (effectiveClassLoader != null) {
            invocation.put(STAGED_CLASSLOADER_KEY, stagedClassLoader);
            invocation.put(WORKING_CLASSLOADER_KEY, effectiveClassLoader);

            Thread.currentThread().setContextClassLoader(effectiveClassLoader);
        }
        try {
            return invoker.invoke(invocation);
        } finally {
            Thread.currentThread().setContextClassLoader(stagedClassLoader);
        }
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        resetClassLoader(invoker, invocation);
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        resetClassLoader(invoker, invocation);
    }

    private void resetClassLoader(Invoker<?> invoker, Invocation invocation) {
        ClassLoader stagedClassLoader = (ClassLoader) invocation.get(STAGED_CLASSLOADER_KEY);
        if (stagedClassLoader != null) {
            Thread.currentThread().setContextClassLoader(stagedClassLoader);
        }
    }
}

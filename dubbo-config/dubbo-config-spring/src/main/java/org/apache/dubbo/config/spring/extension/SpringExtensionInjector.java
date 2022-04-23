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
package org.apache.dubbo.config.spring.extension;

import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.ExtensionAccessor;
import org.apache.dubbo.common.extension.ExtensionInjector;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.ApplicationContext;

import java.util.Arrays;

/**
 * SpringExtensionInjector
 */
public class SpringExtensionInjector implements ExtensionInjector, Lifecycle {
    private static final Logger logger = LoggerFactory.getLogger(SpringExtensionInjector.class);

    public static final String NAME = "spring";

    private ApplicationContext context;

    @Deprecated
    public static void addApplicationContext(ApplicationContext context) {
        /**
         * 关于这个方法 为什么被关闭参考 https://zhuanlan.zhihu.com/p/378320838
         * Dubbo启动时注册JVM钩子函数，完成优雅停机。如果用户使用kill -9 pid 强制关闭指令，是不会执行优雅停机的，只有通过kill pid才会执行。
         *
         *
         * 应用在停机时，接收到关闭通知时，会先把自己标记为不接受（发起）新请求，然后再等待10s（默认是10秒）的时候，等执行中的线程执行完。
         *
         * 那么，之所以他能做这些事，是因为从操作系统、到JVM、到Spring等都对优雅停机做了很好的支持。
         *
         * 关于Dubbo各个版本中具体是如何借助JVM的shutdown hook机制、或者说Spring的事件机制的优雅停机，我的一位同事的一篇文章介绍的很清晰，大家可以看下：
         *
         * https://www.cnkirito.moe/dubbo-gracefully-shutdown/
         *
         * 在从Dubbo 2.5 到 Dubbo 2.7介绍了历史版本中，Dubbo为了解决优雅上下线问题所遇到的问题和方案。
         *
         * 目前，Dubbo中实现方式如下，同样是用到了Spring的事件机制：
         *
         * public class SpringExtensionFactory implements ExtensionFactory {
         *     public static void addApplicationContext(ApplicationContext context) {
         *         CONTEXTS.add(context);
         *         if (context instanceof ConfigurableApplicationContext) {
         *             ((ConfigurableApplicationContext) context).registerShutdownHook();
         *             DubboShutdownHook.getDubboShutdownHook().unregister();
         *         }
         *         BeanFactoryUtils.addApplicationListener(context, SHUTDOWN_HOOK_LISTENER);
         *     }
         * }
         *
         *
         *
         */
//        CONTEXTS.add(context);
//        if (context instanceof ConfigurableApplicationContext) {
//            ((ConfigurableApplicationContext) context).registerShutdownHook();
//            // see https://github.com/apache/dubbo/issues/7093
//            DubboShutdownHook.getDubboShutdownHook().unregister();
//        }
    }

//    @Deprecated
//    public static Set<ApplicationContext> getContexts() {
//        // return contexts;
//        return Collections.emptySet();
//    }

//    @Deprecated
//    public static void clearContexts() {
//        //contexts.clear();
//    }

    public static SpringExtensionInjector get(ExtensionAccessor extensionAccessor) {
        return (SpringExtensionInjector) extensionAccessor.getExtension(ExtensionInjector.class, NAME);
    }

    public ApplicationContext getContext() {
        return context;
    }

    public void init(ApplicationContext context) {
        this.context = context;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getInstance(Class<T> type, String name) {

        if (context == null) {
            // ignore if spring context is not bound
            return null;
        }

        //check @SPI annotation
        if (type.isInterface() && type.isAnnotationPresent(SPI.class)) {
            return null;
        }

        T bean = getOptionalBean(context, name, type);
        if (bean != null) {
            return bean;
        }

        //logger.warn("No spring extension (bean) named:" + name + ", try to find an extension (bean) of type " + type.getName());
        return null;
    }

    private <T> T getOptionalBean(ListableBeanFactory beanFactory, String name, Class<T> type) {
        if (StringUtils.isEmpty(name)) {
            String[] beanNamesForType = beanFactory.getBeanNamesForType(type, true, false);
            if (beanNamesForType != null) {
                if (beanNamesForType.length == 1) {
                    return beanFactory.getBean(beanNamesForType[0], type);
                } else if (beanNamesForType.length > 1) {
                    throw new IllegalStateException("Expect single but found " + beanNamesForType.length + " beans in spring context: " +
                        Arrays.toString(beanNamesForType));
                }
            }
        } else {
            if (beanFactory.containsBean(name)) {
                return beanFactory.getBean(name, type);
            }
        }
        return null;
    }

    @Override
    public void initialize() throws IllegalStateException {
    }

    @Override
    public void start() throws IllegalStateException {
        // no op
    }

    @Override
    public void destroy() {
    }
}

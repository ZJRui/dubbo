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
package org.apache.dubbo.common.extension.inject;

import org.apache.dubbo.common.extension.ExtensionAccessor;
import org.apache.dubbo.common.extension.ExtensionInjector;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;

/**
 * SpiExtensionInjector
 */
public class SpiExtensionInjector implements ExtensionInjector {
    public static final String NAME = "spi";

    private ExtensionAccessor extensionAccessor;

    @Override
    public void setExtensionAccessor(ExtensionAccessor extensionAccessor) {
        this.extensionAccessor = extensionAccessor;
    }

    @Override
    public <T> T getInstance(Class<T> type, String name) {
        if (type.isInterface() && type.isAnnotationPresent(SPI.class)) {
            ExtensionLoader<T> loader = extensionAccessor.getExtensionLoader(type);
            if (loader == null) {
                return null;
            }
            if (!loader.getSupportedExtensions().isEmpty()) {
                /**
                 * 注意这里通过getAdaptiveExtension 返回cachedAdaptiveInstance 也就是扩展点自适应类。
                 * 比如说RegistryProtocol 这个类中有一个setProtocol方法,RegistryProtocol 有一个Protocol protocol属性
                 * 那么RegistryProtocol对象创建的时候注入的Protocol属性就是 Protocol$Adaptive 扩展点自适应类。
                 *
                 * getExtension: 获取普通扩展类
                 * getAdaptiveExtension：获取自适应扩展类
                 * getActivateExtension：获取自动激活的扩展类
                 *
                 */
                return loader.getAdaptiveExtension();
            }
        }
        return null;
    }

}

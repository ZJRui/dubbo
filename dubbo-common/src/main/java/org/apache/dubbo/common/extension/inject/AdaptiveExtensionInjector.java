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

import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionAccessor;
import org.apache.dubbo.common.extension.ExtensionInjector;
import org.apache.dubbo.common.extension.ExtensionLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AdaptiveExtensionInjector
 */
@Adaptive
public class AdaptiveExtensionInjector implements ExtensionInjector, Lifecycle {

    /**
     * 工厂列表也是通过SPI实现的，因此可以在这里获取所有工厂的扩展点加载器
     */
    private List<ExtensionInjector> injectors = Collections.emptyList();

    private ExtensionAccessor extensionAccessor;

    public AdaptiveExtensionInjector() {
    }

    @Override
    public void setExtensionAccessor(ExtensionAccessor extensionAccessor) {
        this.extensionAccessor = extensionAccessor;
    }

    @Override
    public void initialize() throws IllegalStateException {
        /**
         * 工厂列表也是通过SPI实现的，因此可以在这里获取所有工厂的扩展点加载器
         */
        ExtensionLoader<ExtensionInjector> loader = extensionAccessor.getExtensionLoader(ExtensionInjector.class);
        List<ExtensionInjector> list = new ArrayList<ExtensionInjector>();
        for (String name : loader.getSupportedExtensions()) {
            list.add(loader.getExtension(name));
        }
        injectors = Collections.unmodifiableList(list);
    }

    @Override
    public <T> T getInstance(Class<T> type, String name) {
        /**
         * AdaptiveExtensionInjector持有了所有的具体工厂实现。
         * SPIInjector排在前面，SpringInjector排在后面。 先从SPI容器中获取扩展类，然后再从SPring容器中查找。
         *
         */
        for (ExtensionInjector injector : injectors) {
            T extension = injector.getInstance(type, name);
            if (extension != null) {
                return extension;
            }
        }
        return null;
    }

    @Override
    public void start() throws IllegalStateException {

    }

    @Override
    public void destroy() throws IllegalStateException {

    }
}

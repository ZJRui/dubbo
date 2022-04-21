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
package org.apache.dubbo.common.threadpool;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionScope;
import org.apache.dubbo.common.extension.SPI;

import java.util.concurrent.Executor;

import static org.apache.dubbo.common.constants.CommonConstants.THREADPOOL_KEY;

/**
 * ThreadPool
 */
//TODO which scope for ThreadPool? APPLICATION or FRAMEWORK
@SPI(value = "fixed", scope = ExtensionScope.FRAMEWORK)
public interface ThreadPool {

    /**
     * Thread pool
     *
     * 在dubbo线程模型中，为了尽早释放netty的io线程，某些线程模型会把请求投递到线程池进行一步处理，这里所谓的线程池是什么的线程池呢？
     * 其实这里的线程池ThreadPool是一个SPI扩展接口。 Dubbo提供了一些实现。
     * FixedThreadPool：创建一个有固定线程个数的线程池。
     * LimitedThreadPool：创建一个线程池，这个线程池中的线程个数会随着需求动态增加，但是数量不会超过配置的阈值。另外空闲线程不会被回收，会一直存在。
     *
     * EagerThreadPool:创建一个线程池，这个线程池中，当所有核心线程都处于忙碌状态时，将你创阿基你新的线程来执行新任务，而不是把任务放入线程池的阻塞队列中。
     *
     * CachedThreadPool:创建一个自适应线程池，当线程空闲1分钟，线程会被回收，当有新请求到来时，会创建新线程
     *
     * @param url URL contains thread parameter
     * @return thread pool
     */
    @Adaptive({THREADPOOL_KEY})
    Executor getExecutor(URL url);

}

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
package org.apache.dubbo.common.threadpool.support.fixed;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.threadlocal.NamedInternalThreadFactory;
import org.apache.dubbo.common.threadpool.ThreadPool;
import org.apache.dubbo.common.threadpool.support.AbortPolicyWithReport;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_QUEUES;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_THREADS;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_THREAD_NAME;
import static org.apache.dubbo.common.constants.CommonConstants.QUEUES_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREADS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREAD_NAME_KEY;

/**
 * Creates a thread pool that reuses a fixed number of threads
 *
 * @see java.util.concurrent.Executors#newFixedThreadPool(int)
 */
public class FixedThreadPool implements ThreadPool {


    @Override
    public Executor getExecutor(URL url) {
        /***
         * 在dubbo线程模型中，为了尽早释放netty的io线程，某些线程模型会把请求投递到线程池进行一步处理，这里所谓的线程池是什么的线程池呢？
         * 其实这里的线程池ThreadPool是一个SPI扩展接口。 Dubbo提供了一些实现。
         * FixedThreadPool：创建一个有固定线程个数的线程池。
         * LimitedThreadPool：创建一个线程池，这个线程池中的线程个数会随着需求动态增加，但是数量不会超过配置的阈值。另外空闲线程不会被回收，会一直存在。
         *
         * EagerThreadPool:创建一个线程池，这个线程池中，当所有核心线程都处于忙碌状态时，将你创阿基你新的线程来执行新任务，而不是把任务放入线程池的阻塞队列中。
         *
         * CachedThreadPool:创建一个自适应线程池，当线程空闲1分钟，线程会被回收，当有新请求到来时，会创建新线程
         */
        String name = url.getParameter(THREAD_NAME_KEY, (String) url.getAttribute(THREAD_NAME_KEY, DEFAULT_THREAD_NAME));
        int threads = url.getParameter(THREADS_KEY, DEFAULT_THREADS);
        int queues = url.getParameter(QUEUES_KEY, DEFAULT_QUEUES);
        /**
         * 这里将ThreadPoolExecutor的核心线程个数和最大线程个数都设置为threads，所以创建的线程池是固定线程个数的线程池。
         *
         * 当队列元素为0时，阻塞队列使用的是SynchronousQueue
         * 当队列长度小于0时和大于零 使用的是无界阻塞队列LinkedBlockingQueue
         *
         * AbortPolicyWithReport  饱和策略：意味着当线程池队列慢并且线程池中线程都忙绿时先来的任务会被丢弃且报出RejectedExecutionException
         *
         */
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                queues == 0 ? new SynchronousQueue<Runnable>() :
                        (queues < 0 ? new LinkedBlockingQueue<Runnable>()
                                : new LinkedBlockingQueue<Runnable>(queues)),
                new NamedInternalThreadFactory(name, true), new AbortPolicyWithReport(name, url));
    }

}

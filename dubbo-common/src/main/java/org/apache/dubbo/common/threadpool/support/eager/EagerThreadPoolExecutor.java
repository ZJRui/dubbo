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

package org.apache.dubbo.common.threadpool.support.eager;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * EagerThreadPoolExecutor
 */
public class EagerThreadPoolExecutor extends ThreadPoolExecutor {

    public EagerThreadPoolExecutor(int corePoolSize,
                                   int maximumPoolSize,
                                   long keepAliveTime,
                                   TimeUnit unit, TaskQueue<Runnable> workQueue,
                                   ThreadFactory threadFactory,
                                   RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
    }

    @Override
    public void execute(Runnable command) {
        if (command == null) {
            throw new NullPointerException();
        }

        /**
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
         * ======================================
         *
         *
         *
         * EagerThreadPool实现了ThreadPool接口，其getExecutor创建的是EagerThreadPoolExecutor，它使用的queue为TaskQueue，
         * 这个TaskQueue是实现的核心
         *
         *
         * EagerThreadPoolExecutor继承了ThreadPoolExecutor，它维护了submittedTaskCount，在执行任务之前递增，
         * 在afterExecute的时候胡递减；其execute方法会捕获RejectedExecutionException，
         * 然后使用TaskQueue的retryOffer再重新入队，入队不成功才抛出RejectedExecutionException
         *
         *
          EagerThreadPoolExecutor和JUC包中的ThreadPoolExecutor的不同之处在于，提交任务时先判断线程池是否核心线程已满，如果没有则创建新的核心线程执行任务
         如果核心线程已满，则入队，如果队列已满导致入队不成功则创建新的worker线程，如果worker线程已满创建失败则抛出异常。 也就是说
          JUC的队列满了之后才会开启新的线程来处理任务（前提是核心线程已满的情况下，且线程池线程数量没超过最大线程数量）

          EagerThreadPoolExecutor 当线程池核心线程已满，新来的任务不会被放入线程池队列，而是会开启新线程来执行处理任务。 这个是如何做到的呢？
          其实就是 TaskQueue在入队的时候发现 如果当前线程池的线程数小于最大线程数 则返回false表示入队失败，从而 创建新的线程。
          创建新的线程的时候如果发现线程池中线程已经到了最大值 导致创建也失败了就会抛出异常  这个时候我们捕获这个异常 将任务放入队列中。
         *
         *
         *
         *
         */
        try {
            /**
             * super 的execute方法会  小于核心线程数的时候创建新的线程
             * 大于核心线程数的时候会 进行offer入队，但是我们这里使用的是TaskQueue。
             * TaskQueue继承了LinkedBlockingQueue，它覆盖了offer方法，该方法在submittedTaskCount小于poolSize的时候会入队，
             * 如果大于等于poolSize则再判断currentPoolThreadSize是否小于maximumPoolSize，
             * 如果小于则返回false(表示入队失败，父类的execute发现入队失败会创建新的线程)让线程池创建新线程，
             * 最后在currentPoolThreadSize大于等于maximumPoolSize的时候入队
             *
             * Dubbo实现的线程池重写了队列的入队offer逻辑，当线程池中正在执行任务的线程数量小于线程池已有线程数量的时候 入队，
             * 当已有队列数量小于最大线程数量的时候返回false入队失败。入队失败会导致线程池创建新的线程。
             * 在创建新的线程时如果发现了线程池达到了最大线程数量则会抛出异常，需要将这个异常捕获，并执行队列的入队。
             * 从而实现当线程池线程没有达到最大线程数量的时候就会一直创建新的线程。如果达到了最大线程数量则入队。
             * 从这种意义上 JUC标准的线程池ThreadPool只需要将最大线程数量和核心线程数量设置为一样就能实现同样 效果。
             *
             *
             */
            super.execute(command);
        } catch (RejectedExecutionException rx) {
            // retry to offer the task into queue.
            final TaskQueue queue = (TaskQueue) super.getQueue();
            try {
                if (!queue.retryOffer(command, 0, TimeUnit.MILLISECONDS)) {
                    throw new RejectedExecutionException("Queue capacity is full.", rx);
                }
            } catch (InterruptedException x) {
                throw new RejectedExecutionException(x);
            }
        }
    }
}

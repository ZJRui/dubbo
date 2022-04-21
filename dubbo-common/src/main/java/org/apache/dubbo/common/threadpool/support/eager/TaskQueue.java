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

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * TaskQueue in the EagerThreadPoolExecutor
 * It offer a task if the executor's submittedTaskCount less than currentPoolThreadSize
 * or the currentPoolThreadSize more than executor's maximumPoolSize.
 * That can make the executor create new worker
 * when the task num is bigger than corePoolSize but less than maximumPoolSize.
 */
public class TaskQueue<R extends Runnable> extends LinkedBlockingQueue<Runnable> {

    private static final long serialVersionUID = -2635853580887179627L;

    private EagerThreadPoolExecutor executor;

    public TaskQueue(int capacity) {
        super(capacity);
    }

    public void setExecutor(EagerThreadPoolExecutor exec) {
        executor = exec;
    }

    @Override
    public boolean offer(Runnable runnable) {
        if (executor == null) {
            throw new RejectedExecutionException("The task queue does not have executor!");
        }

        int currentPoolThreadSize = executor.getPoolSize();
        // have free worker. put task into queue to let the worker deal with task.
        /**
         * 如果提交线程池的任务个数小于当前线程池的线程个数，则提交到队列，让空闲线程处理
         *
         * getActiveCount；Returns the approximate number of threads that are actively executing tasks.
         * 返回正在主动执行任务的线程的大致数量。
         * getPoolSize：Returns the current number of threads in the pool. 返回池中的当前线程数。
         */
        if (executor.getActiveCount() < currentPoolThreadSize) {
            return super.offer(runnable);
        }
        /***
         * 如果当前线程池个数小于线程池的最大线程个数，则返回false
         */

        // return false to let executor create new worker.
        if (currentPoolThreadSize < executor.getMaximumPoolSize()) {
            return false;
        }

        /**
         * 如果当前线程个数大于等于线程池设置的最大个数，则添加到队列。
         */
        // currentPoolThreadSize >= max
        return super.offer(runnable);
    }

    /**
     * retry offer task
     *
     * @param o task
     * @return offer success or not
     * @throws RejectedExecutionException if executor is terminated.
     */
    public boolean retryOffer(Runnable o, long timeout, TimeUnit unit) throws InterruptedException {
        if (executor.isShutdown()) {
            throw new RejectedExecutionException("Executor is shutdown!");
        }
        return super.offer(o, timeout, unit);
    }
}

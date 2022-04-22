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
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.Node;

/**
 * Invoker. (API/SPI, Prototype, ThreadSafe)
 *
 * @see org.apache.dubbo.rpc.Protocol#refer(Class, org.apache.dubbo.common.URL)
 * @see org.apache.dubbo.rpc.InvokerListener
 * @see org.apache.dubbo.rpc.protocol.AbstractInvoker
 */
public interface Invoker<T> extends Node {

    /**
     * get service interface.
     *
     * @return service interface.
     */
    Class<T> getInterface();

    /**
     *
     * 服务的发布是 首先将 服务实现类转为Invoker对象，简单理解Invoker对象内部持有服务实现类。
     * 你只需要告知Invoker对象要执行服务实现了的哪个方法就可以完成调用。然后在将Invoker对象使用具体的协议对外暴露，
     * 如果使用的是Dubbo协议，那么就会启动NettyServer对外暴露端口。NettyServer接收到方法调用请求的时候会通过invoker执行服务实现类的方法。
     *
     *
     * 对于服务消费，就是先获取对服务的引用。获取到的服务的引用本质上是一个代理对象，当调用服务的方法的时候 ，
     * 代理拦截器将方法调用信息封装成请求 交给Invoker，Invoker底层再通过nettyClient 将请求发送出去。
     *
     *
     * 对于这个Inovker的理解： 他表示执行特定对象的某一个方法。从服务提供者的角度来看： 消费方的请求参数就是要执行的方法信息，
     * 请求作为Invoker的入参，Invoker内部如何实现对真实对象的调用呢？Invoker对象内部持有服务实现类。从消费者的角度看：
     * 消费者执行接口方法会被拦截封装成 方法调用请求，交给Invoker对象，Invoker对象内部再通过NettyClient发从请求。
     *
     *
     * @param invocation
     * @return result
     * @throws RpcException
     */
    Result invoke(Invocation invocation) throws RpcException;

    /**
     * destroy all
     */
    default void destroyAll() {
        destroy();
    }

}

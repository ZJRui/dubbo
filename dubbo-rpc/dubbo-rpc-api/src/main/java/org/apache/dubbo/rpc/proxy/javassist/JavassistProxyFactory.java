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
package org.apache.dubbo.rpc.proxy.javassist;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.bytecode.Proxy;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.proxy.AbstractProxyFactory;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;
import org.apache.dubbo.rpc.proxy.InvokerInvocationHandler;
import org.apache.dubbo.rpc.proxy.jdk.JdkProxyFactory;

import java.util.Arrays;

/**
 * JavassistRpcProxyFactory
 */
public class JavassistProxyFactory extends AbstractProxyFactory {
    private final static Logger logger = LoggerFactory.getLogger(JavassistProxyFactory.class);
    private final JdkProxyFactory jdkProxyFactory = new JdkProxyFactory();

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Invoker<T> invoker, Class<?>[] interfaces) {
        try {
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
            return (T) Proxy.getProxy(interfaces).newInstance(new InvokerInvocationHandler(invoker));
        } catch (Throwable fromJavassist) {
            // try fall back to JDK proxy factory
            try {
                T proxy = jdkProxyFactory.getProxy(invoker, interfaces);
                logger.error("Failed to generate proxy by Javassist failed. Fallback to use JDK proxy success. " +
                    "Interfaces: " + Arrays.toString(interfaces), fromJavassist);
                return proxy;
            } catch (Throwable fromJdk) {
                logger.error("Failed to generate proxy by Javassist failed. Fallback to use JDK proxy is also failed. " +
                    "Interfaces: " + Arrays.toString(interfaces) + " Javassist Error.", fromJavassist);
                logger.error("Failed to generate proxy by Javassist failed. Fallback to use JDK proxy is also failed. " +
                    "Interfaces: " + Arrays.toString(interfaces) + " JDK Error.", fromJdk);
                throw fromJavassist;
            }
        }
    }

    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) {
        try {
            // TODO Wrapper cannot handle this scenario correctly: the classname contains '$'
            /**
             * Dubbo 会给每个服务提供者的实现类生产者一个Wrapper类，这个Wrapper类中最终调用服务提供者的接口实现类。
             * 注意是给服务提供者的实现类 动态生成一个wrapper，而不是给扩展SPI接口的实现类。
             *
             * Wrapper类的存在是为了减少反射的调用。当服务提供者收到消费方发来的请求后，需要根据消费者传递过来的方法名和参数反射调用
             * 服务器提供者的实现类，而反射本身是有性能开销的，Dubbo把每个服务提供者的实现类通过JavaAssist包装为一个Wrapper类来减少
             * 反射调用开销。那么Wrapper类为何能减少反射调用呢？
             *
             *
             *
             *服务提供实现类 GreetingServiceImpl  implements GreetingService
             * 对应的Wrapper类的源码如下
             *
             * public class Wrapper1 extends Wrapper implemnts ClassGenerator.DC{
             *     .....
             *     public Object invokeMethod(Object object, String string, Class[] arrClass, Object[] arrObject) throws InvocationTargetException {
             *         GreetingServiceImpl greetingServiceImpl;
             *         try {
             *             greetingServiceImpl = (GreetingServiceImpl)object;
             *         } catch (Throwable var8) {
             *             throw new IllegalArgumentException(var8);
             *         }
             *
             *         try {
             *             if ("findUserList".equals(string) && var3.length == 0) {
             *                 return greetingServiceImpl.findUserList();
             *             }
             *
             *             if ("findById".equals(string) && var3.length == 1) {
             *                 return greetingServiceImpl.findById((Integer)var4[0]);
             *             }
             *
             *             if ("updateById".equals(string) && var3.length == 1) {
             *                 greetingServiceImpl.updateById((Integer)var4[0]);
             *                 return null;
             *             }
             *         } catch (Throwable var9) {
             *             throw new InvocationTargetException(var9);
             *         }
             *
             *         throw new NoSuchMethodException("Not found method \"" + var2 + "\" in class com.xx.xx.samples.loader.service.impl.UserServiceImpl.");
             *     }
             *
             * }
             *
             * Wrapper1类的invokeMethod方法最终直接调用的是GreetingServiceImpl的具体方法，这就避免了反射开销，而Wrapper1类是
             * Dubbo服务启动时生成的，所以不会对运行时带来开销。
             *
             *
             * getInvoker方法什么时候被调用呢？
             * 在ServiceConfig的doExportUrl方法中会执行如下内容
             * Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
             * 其中ServiceConfig<GreetingService>中会有一个成员属性 ref 表示服务提供者实现类对象
             * 也就是说getInvoker方法的第一个参数就是真实的业务服务提供者对象，在ProxyFactory的getInvoker方法中
             * 我们将这个服务提供者包装到一个Invoker对象中
             *
             * 这个Invoker对象又被Protocol扩展接口对外暴露
             *  Exporter<?> exporter = protocolSPI.export(invoker);
             *
             *
             * 服务提供者先被包括成Wrapper，然后Wrapper又被放置到Invoker中，对外以Invoker的形式提供（这句话存在缺点）。
             * 在创建Invoker对象的时候我们将服务提供者传递给了Invoker对象 而且将服务提供者的wrapper也传递给了Invoker，
             * 因此当框架内要执行服务提供者的某个方法，
             * 只需要告知Invoker要执行方法的名称和参数信息，Invoker接口的 invoke方法的参数Invocation 就封装了这样的信息。
             *
             *  实际上我们拿到Invoker对象之后，当消费者请求过来的时候我们只需要将 消费者要调用的方法信息告知Invoker就可以了
             *
             *  虽然invoker对象内部 既有 业务服务提供者实现类，也有要执行的方法信息和参数信息，但是invoker的invoke内部
             *  并没有使用反射对业务服务提供者实现类进行反射调用其方法。而是交给了服务提供者的Wrapper对象，这个Wrapper对象拿到
             *  服务提供者之后会直接调用其对应的方法。
             *
             *  Invoker的invoke方法 用来执行服务提供者的某个方法，在invoke方法内部会执行invoker对象的doInvoker方法，也就是下面我们
             *  重写的AbstractProxyInvoker对象的doInvoker方法。Invoker对象本身持有 服务提供者对象的引用，因此在调用doInvoker的时候
             *  会将服务提供者作为doInvoker的第一个参数传递到doInvoker方法内。
             *  同时这里的AbstractProxyInvoker对象 引用了外部的 服务提供者Wrapper对象，因此在doInvoker内 我们执行了
             *  服务提供者Wrapper对象的invokeMethod，同时将服务提供者对象作为invokeMethod的第一个参数，因此服务提供者Wrapper才能
             *  拿到服务提供者对象。
             *
             *
             *  服务提供者的Wrapper对象并不直接持有 服务提供者对象， 那么我们要解决如何将服务提供者传递给Wrapper对象，然后wrapper对象内
             *  使用服务提供者来执行指定方法的问题。
             *  在下面我们创建AbstractProxyInvoker对象对象的时候 将服务提供者 传递给了Invoker对象。同时doInvoker方法内部使用到了Wrapper对象，也就
             *  意味着将服务提供者的Wrapper对象也传递给了Invoker。
             *  当Invoker的invoke方法被通知执行某一个方法的时候， 他会执行服务提供者Wrapper的invokeMethod，同时传递服务提供者
             *  对象给这个Wrapper。
             *
             *  那么问题 就是 Invoker对象的invoke方法 何时被调用？ 也就是什么时候在哪里收到  触发Invoker的invoke执行
             *  从而执行wrapper的invokeMethod，从而执行服务提供者的方法？
             *
             *  服务发布的时候虽然得到了Invoker对象，还需要使用Protocol对象对外发布
             *  Exporter<?> exporter = protocolSPI.export(invoker);
             *  对于DubboProtocol而言，在其export方法中会创建一个DubboExporter对象，这个DubboExporter对象中持有Invoker对象。
             *  同时DubboProtocol的export在创建Export对象的时候 会将DubboProtocol对象的一个Map<String, Exporter<?>> exporterMap
             *  属性传递给DubboExporter对象，然后DubboExporter对象将自身放置到这个Map中。 这个map的key是serviceKey。
             *  export的时候还会创建一个nettyServer.
             *  DubboProtocol对象中有一个ExchangeHandler requestHandler， DubboProtocol的exptor方法会针对不同的服务只开启一个nettyServer。
             *  在创建NettyServer的时候会 传递 requestHandler给NettyServer，因此当消息来临的时候就会执行 requestHandler的方法
             *
             *  当服务提供者端收到消息请求的时候会执行org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol#requestHandler 的reply方法。
             *  requestHandler首先从请求中取出serviceKey，然后在DubboProtocol对象的Map中根据serviceKey找到 DubboExporter对象，
             *  然后根据export对象找到Invoker对象，然后读取请求中要执行的方法名称和参数等信息封装成Invocation。最终执行Invoker的invoke方法。
             *
             *
             *
             *
             *
             *
             *
             */
            final Wrapper wrapper = Wrapper.getWrapper(proxy.getClass().getName().indexOf('$') < 0 ? proxy.getClass() : type);
            return new AbstractProxyInvoker<T>(proxy, type, url) {
                @Override
                protected Object doInvoke(T proxy, String methodName,
                                          Class<?>[] parameterTypes,
                                          Object[] arguments) throws Throwable {
                    /**
                     * 返回Invoker对象有什么作用？？
                     *
                     * Dubbo在创建Invoker的时候先将ref实现类包装成了一个Wrapper,
                     * 然后Invoker被调用的时候会触发doInvoke()方法,然
                     * 后调用Wrapper的invokeMethod()方法。由于Wrapper是一个抽象类,
                     * 故Wrapper.getWrapper()被调用的时候肯定是利用了字节码增强的技术为Wrapper创建了一个实现类。
                     *
                     *
                     */
                    return wrapper.invokeMethod(proxy, methodName, parameterTypes, arguments);
                }
            };
        } catch (Throwable fromJavassist) {
            // try fall back to JDK proxy factory
            try {
                Invoker<T> invoker = jdkProxyFactory.getInvoker(proxy, type, url);
                logger.error("Failed to generate invoker by Javassist failed. Fallback to use JDK proxy success. " +
                    "Interfaces: " + type, fromJavassist);
                // log out error
                return invoker;
            } catch (Throwable fromJdk) {
                logger.error("Failed to generate invoker by Javassist failed. Fallback to use JDK proxy is also failed. " +
                    "Interfaces: " + type + " Javassist Error.", fromJavassist);
                logger.error("Failed to generate invoker by Javassist failed. Fallback to use JDK proxy is also failed. " +
                    "Interfaces: " + type + " JDK Error.", fromJdk);
                throw fromJavassist;
            }
        }
    }

}

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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.URL;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Provide helpful information for {@link ExtensionLoader} to inject dependency extension instance.
 *
 * @author lenovo
 * @see ExtensionLoader
 * @see URL
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Adaptive {

    /**
     *通过set方法为 instance设置属性值。   instance的属性被赋值的条件是 （1）有set方法（2）有对应的扩展点实例对象。
     *
     * 如果某个扩展类是另外一个扩展类的成员属性，并且拥有set方法，那么框架会自动注入对应的扩展点实例。但是这里存在一个问题 ，如果扩展属性是一个接口，
     * 这个接口有多个实现，那么具体注入哪个呢？ 这就涉及自适应特性。我们使用@Adaptive 注解，可以动态通过URL中的参数来确定要使用哪个具体的实现类，从而
     * 解决自动加载中的实例注入的问题。 比如
     * @SPI(value = "netty", scope = ExtensionScope.FRAMEWORK)
     * public interface Transporter {
     *@Adaptive({Constants.SERVER_KEY, Constants.TRANSPORTER_KEY})
     *RemotingServer bind (URL url, ChannelHandler handler) throws RemotingException;
     * }
     *
     * @Adaptive传入了两个 Constants中的参数，server和transport。当外部调用Transport.bind 方法时，会动态从传入的参数URL中提取key参数 server的value
     * 值，如果能匹配上某个扩展实现类则直接使用对应的实现类。如果未匹配上，则继续通过第二个key参数 transport 提取value值。 如果都没匹配上，则抛出异常。也就是
     * 说如果@Adaptive中传入了多个参数，则一次进行实现类的匹配，直到最后抛出异常。
     *
     * --------------------------------
     * @Adaptive注解 可以标记在类、接口、枚举类和方法上。但是在Dubbo整个框架中，只有几个地方使用在类级别上，如AdaptiveExtensionFactory和AdaptiveCompiler，其余都
     * 标注在方法上。 如果标注在接口方法上，即方法级别注解，则可以通过参数动态获得实现类。
     * 方法级别注解在第一次getExtension时，会自动生成和编译一个动态的Adaptive类，从而达到动态实现类的效果。
     *
     * 例如Transport接口在bind和connect两个方法上添加了@Adaptive注解，bind方法@Adaptive注解中有两个参数：
     * @Adaptive({Constants.SERVER_KEY, Constants.TRANSPORTER_KEY})
     * 。 Dubbo在初始化扩展点时，会生成一个Transport$Adaptive类，里面会实现这两个方法，方法中会根据@Adaptive注解指定的参数名称，从URL中获取
     * 这个名称对应的value， 将这个value作为name获取对应的Transport实现实例。
     *
     *    public org.apache.dubbo.remoting.RemotingServer bind(org.apache.dubbo.common.URL arg0, org.apache.dubbo.remoting.ChannelHandler arg1) throws org.apache.dubbo.remoting.RemotingException {
     *         if (arg0 == null) throw new IllegalArgumentException("url == null");
     *         org.apache.dubbo.common.URL url = arg0;
     *         **
     *          * 问题： 这里从url中获取 transport参数，  Transport$Adaptive 类是动态生成的，他是怎么知道需要获取Transport参数，通过
     *          * 这个Transport参数来确定使用哪个扩展接口实现类的？
     *          * 这就是@Adaptive 实现的Dubbo 扩展点自适应功能。
     *          *
     *          *
     *         String extName = url.getParameter("server", url.getParameter("transporter", "netty"));
     *         if (extName == null)
     *             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.remoting.Transporter) name from url (" + url.toString() + ") use keys([server, transporter])");
     *         ScopeModel scopeModel = ScopeModelUtil.getOrDefault(url.getScopeModel(), org.apache.dubbo.remoting.Transporter.class);
     *         **
     *          * 根据url中指定的Transport参数获取指定的实现
     *          *
     *         org.apache.dubbo.remoting.Transporter extension = (org.apache.dubbo.remoting.Transporter) scopeModel.getExtensionLoader(org.apache.dubbo.remoting.Transporter.class).getExtension(extName);
     *         return extension.bind(arg0, arg1);
     *     }
     *
     *
     *
     * ------
     * 如果@Adaptive注解放在实现类上，则真个实现类会直接作为 扩展点自适应类，不再生成$Adaptive。在扩展点接口的多个实现中，只能有一个实现类上可以加@Adaptive注解。
     * ----------------------
     * ExtensionLoader（一个ExtensionLoader对应一个扩展点接口）中会缓存两个与@Adaptive有关的对象，一个缓存在cachedAdaptiveClass中，即Adaptive具体实现类的class类型。
     * 另一个缓存在cachedAdaptiveInstance中，即class的具体实例化对象。
     * 在扩展点初始化时，如果发现实现类上有@Adaptive注解，则直接赋值给cachedAdaptiveClass，后续实例化类的时候就不会再动态生成代码，直接实例化cachedAdaptiveClass，并
     * 把实例缓存到cachedAdaptiveInstance中。
     * 如果注解在接口方法上，则会根据参数，动态获得扩展点的实现是，生成Adaptive类，再缓存到cachedAdaptiveInstance中。
     *
     *
     * ============================
     * 注意：  如果 Transport的bind方法 上的@Adaptive注解没有传入key参数，则默认会把类名转为key。 如扩展类命是SimpleExt，会转化为 simple.ext
     * 扩展类名是transport，则会转为transport。然后在url中根据这个key 获取对应的扩展点的实现名称。
     *   @Adaptive()
     *   RemotingServer bind(URL url, ChannelHandler handler) throws RemotingException;
     *
     *   Transport$Adaptive的bind方法中会如下  String extName=url.getParameter("transport","defaultValue")//这个defaultValue是 Transport的SPI注解中指定的值
     *   Tansport res= ExtensionLaoder.getExtensionLoader(Transport.class).getExtension(extname)

     *   针对SimpleExt接口就是：String extName = url.getParameter("simple.ext""impll");
     *
     *
     *
     */





    /**
     * Decide which target extension to be injected. The name of the target extension is decided by the parameter passed
     * in the URL, and the parameter names are given by this method.
     * <p>
     * If the specified parameters are not found from {@link URL}, then the default extension will be used for
     * dependency injection (specified in its interface's {@link SPI}).
     * <p>
     * For example, given <code>String[] {"key1", "key2"}</code>:
     * <ol>
     * <li>find parameter 'key1' in URL, use its value as the extension's name</li>
     * <li>try 'key2' for extension's name if 'key1' is not found (or its value is empty) in URL</li>
     * <li>use default extension if 'key2' doesn't exist either</li>
     * <li>otherwise, throw {@link IllegalStateException}</li>
     * </ol>
     * If the parameter names are empty, then a default parameter name is generated from interface's
     * class name with the rule: divide classname from capital char into several parts, and separate the parts with
     * dot '.', for example, for {@code org.apache.dubbo.xxx.YyyInvokerWrapper}, the generated name is
     * <code>String[] {"yyy.invoker.wrapper"}</code>.
     *
     * @return parameter names in URL
     */
    String[] value() default {};

}

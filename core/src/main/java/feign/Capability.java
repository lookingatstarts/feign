/*
 * Copyright © 2012 The Feign Authors (feign@commonhaus.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feign;

import feign.Logger.Level;
import feign.Request.Options;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;

/**
 * 在构造客户端时，可以增强部分组件
 * Capabilities expose core feign artifacts to implementations so parts of core can be customized
 * around the time the client being built.
 *
 * <p>For instance, capabilities take the {@link Client}, make changes to it and feed the modified
 * version back to feign.
 *
 * @see Metrics5Capability
 */
public interface Capability {

  /**
   * @param componentToEnrich 被增强的对象
   * @param capabilityToEnrich 被增强对象的类型
   * @param capabilities 增强类
   */
  static Object enrich(Object componentToEnrich,
                       Class<?> capabilityToEnrich,
                       List<Capability> capabilities) {
    return capabilities.stream()
        .reduce(
            componentToEnrich,
            (target, capability) -> invoke(target, capability, capabilityToEnrich),
            (component, enrichedComponent) -> enrichedComponent);
  }

  static Object invoke(Object target, Capability capability, Class<?> capabilityToEnrich) {
    return Arrays.stream(capability.getClass().getMethods())
        // 找到增强的方法
        .filter(method -> method.getName().equals("enrich"))
        .filter(method -> method.getReturnType().isAssignableFrom(capabilityToEnrich))
        .findFirst()
        .map(
            method -> {
              try {
                // 增强对象
                return method.invoke(capability, target);
              } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw new RuntimeException("Unable to enrich " + target, e);
              }
            })
        .orElse(target);
  }

  /**
   * 增强方法
   */
  default Client enrich(Client client) {
    return client;
  }

  default AsyncClient<Object> enrich(AsyncClient<Object> client) {
    return client;
  }

  default Retryer enrich(Retryer retryer) {
    return retryer;
  }

  default RequestInterceptor enrich(RequestInterceptor requestInterceptor) {
    return requestInterceptor;
  }

  default ResponseInterceptor enrich(ResponseInterceptor responseInterceptor) {
    return responseInterceptor;
  }

  default ResponseInterceptor.Chain enrich(ResponseInterceptor.Chain chain) {
    return chain;
  }

  default Logger enrich(Logger logger) {
    return logger;
  }

  default Level enrich(Level level) {
    return level;
  }

  default Contract enrich(Contract contract) {
    return contract;
  }

  default Options enrich(Options options) {
    return options;
  }

  default Encoder enrich(Encoder encoder) {
    return encoder;
  }

  default Decoder enrich(Decoder decoder) {
    return decoder;
  }

  default ErrorDecoder enrich(ErrorDecoder decoder) {
    return decoder;
  }

  default InvocationHandlerFactory enrich(InvocationHandlerFactory invocationHandlerFactory) {
    return invocationHandlerFactory;
  }

  default QueryMapEncoder enrich(QueryMapEncoder queryMapEncoder) {
    return queryMapEncoder;
  }

  default AsyncResponseHandler enrich(AsyncResponseHandler asyncResponseHandler) {
    return asyncResponseHandler;
  }

  default <C> AsyncContextSupplier<C> enrich(AsyncContextSupplier<C> asyncContextSupplier) {
    return asyncContextSupplier;
  }

  default MethodInfoResolver enrich(MethodInfoResolver methodInfoResolver) {
    return methodInfoResolver;
  }
}

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

import static feign.Util.checkNotNull;

import feign.InvocationHandlerFactory.MethodHandler;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 反射实现的Feign
 * @param <C>
 */
public class ReflectiveFeign<C> extends Feign {

  private final ParseHandlersByName<C> targetToHandlersByName;
  private final InvocationHandlerFactory factory;
  // 异步上下文
  private final AsyncContextSupplier<C> defaultContextSupplier;

  ReflectiveFeign(
      Contract contract,
      MethodHandler.Factory<C> methodHandlerFactory,
      InvocationHandlerFactory invocationHandlerFactory,
      AsyncContextSupplier<C> defaultContextSupplier) {
    this.targetToHandlersByName = new ParseHandlersByName<C>(contract, methodHandlerFactory);
    this.factory = invocationHandlerFactory;
    this.defaultContextSupplier = defaultContextSupplier;
  }

  /**
   * creates an api binding to the {@code target}. As this invokes reflection, care should be taken
   * to cache the result.
   */
  public <T> T newInstance(Target<T> target) {
    return newInstance(target, defaultContextSupplier.newContext());
  }

  /**
   * 创建代理类
   */
  @SuppressWarnings("unchecked")
  public <T> T newInstance(Target<T> target, C requestContext) {

    TargetSpecificationVerifier.verify(target);
    // 解析方法对应的MethodHandler
    Map<Method, MethodHandler> methodToHandler = targetToHandlersByName.apply(target, requestContext);
    InvocationHandler handler = factory.create(target, methodToHandler);
    T proxy = (T) Proxy.newProxyInstance(target.type().getClassLoader(), new Class<?>[] {target.type()}, handler);
    for (MethodHandler methodHandler : methodToHandler.values()) {
      if (methodHandler instanceof DefaultMethodHandler) {
        ((DefaultMethodHandler) methodHandler).bindTo(proxy);
      }
    }
    return proxy;
  }

  // InvocationHandler
  static class FeignInvocationHandler implements InvocationHandler {
    // 目标接口
    private final Target target;
    // 方法处理器
    private final Map<Method, MethodHandler> dispatch;

    FeignInvocationHandler(Target target, Map<Method, MethodHandler> dispatch) {
      this.target = checkNotNull(target, "target");
      this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if ("equals".equals(method.getName())) {
        try {
          Object otherHandler = args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
          return equals(otherHandler);
        } catch (IllegalArgumentException e) {
          return false;
        }
      } else if ("hashCode".equals(method.getName())) {
        return hashCode();
      } else if ("toString".equals(method.getName())) {
        return toString();
      } else if (!dispatch.containsKey(method)) {
        throw new UnsupportedOperationException(String.format("Method \"%s\" should not be called", method.getName()));
      }
      // 调用方法，通过method获取到对应的MethodHandler进行调用
      return dispatch.get(method).invoke(args);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof FeignInvocationHandler) {
        FeignInvocationHandler other = (FeignInvocationHandler) obj;
        return target.equals(other.target);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return target.hashCode();
    }

    @Override
    public String toString() {
      return target.toString();
    }
  }


  private static final class ParseHandlersByName<C> {
    private final Contract contract;
    private final MethodHandler.Factory<C> factory;

    ParseHandlersByName(Contract contract, MethodHandler.Factory<C> factory) {
      this.contract = contract;
      this.factory = factory;
    }

    public Map<Method, MethodHandler> apply(Target target, C requestContext) {
      final Map<Method, MethodHandler> result = new LinkedHashMap<>();
      // 解析方法元数据
      final List<MethodMetadata> metadataList = contract.parseAndValidateMetadata(target.type());
      for (MethodMetadata md : metadataList) {
        final Method method = md.method();
        if (method.getDeclaringClass() == Object.class) {
          continue;
        }
        final MethodHandler handler = createMethodHandler(target, md, requestContext);
        result.put(method, handler);
      }
      for (Method method : target.type().getMethods()) {
        if (Util.isDefault(method)) {
          final MethodHandler handler = new DefaultMethodHandler(method);
          result.put(method, handler);
        }
      }
      return result;
    }

    /**
     * MethodHandler
     */
    private MethodHandler createMethodHandler(
        final Target<?> target, final MethodMetadata md, final C requestContext) {
      if (md.isIgnored()) {
        return args -> {
          throw new IllegalStateException(md.configKey() + " is not a method handled by feign");
        };
      }
      return factory.create(target, md, requestContext);
    }
  }

  /**
   * 校验接口
   */
  private static class TargetSpecificationVerifier {

    public static <T> void verify(Target<T> target) {
      Class<T> type = target.type();
      // 必须是接口
      if (!type.isInterface()) {
        throw new IllegalArgumentException("Type must be an interface: " + type);
      }
      // 接口的方法
      for (final Method m : type.getMethods()) {
        final Class<?> retType = m.getReturnType();
        if (!CompletableFuture.class.isAssignableFrom(retType)) {
          continue; // synchronous case
        }
        if (retType != CompletableFuture.class) {
          throw new IllegalArgumentException("Method return type is not CompletableFuture: " + getFullMethodName(type, retType, m));
        }
        final Type genRetType = m.getGenericReturnType();
        if (!(genRetType instanceof ParameterizedType)) {
          throw new IllegalArgumentException("Method return type is not parameterized: " + getFullMethodName(type, genRetType, m));
        }
        if (((ParameterizedType) genRetType).getActualTypeArguments()[0] instanceof WildcardType) {
          throw new IllegalArgumentException("Wildcards are not supported for return-type parameters: " + getFullMethodName(type, genRetType, m));
        }
      }
    }

    private static String getFullMethodName(Class<?> type, Type retType, Method m) {
      return retType.getTypeName() + " " + type.toGenericString() + "." + m.getName();
    }
  }
}

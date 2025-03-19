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

import static feign.ExceptionPropagationPolicy.UNWRAP;
import static feign.FeignException.errorExecuting;
import static feign.Util.checkNotNull;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.Request.Options;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

final class SynchronousMethodHandler implements MethodHandler {

  private final Client client;
  private final ResponseHandler responseHandler;
  private final MethodHandlerConfiguration configuration;

  private SynchronousMethodHandler(
      MethodHandlerConfiguration configuration,
      Client client,
      ResponseHandler responseHandler) {
    this.configuration =
        checkNotNull(configuration, "methodHandlerConfiguration");
    this.client = checkNotNull(client, "client for %s", configuration.getTarget());
    this.responseHandler = responseHandler;
  }

  @Override
  public Object invoke(Object[] argv) throws Throwable {
    // 解析RequestTemplate,用argv参数去替换下模板变量，请求体，queryMap 等等
    // requestTemplateFactory内部会有methodMetadata的引用，一个method会有一个Factory
    RequestTemplate.Factory requestTemplateFactory = configuration.getRequestTemplateFactory();
    // 替换模版值
    RequestTemplate template = requestTemplateFactory.create(argv);
    // 超时时间
    Options options = findOptions(argv);
    // retryer重试器
    Retryer retryer = this.configuration.getRetryer().clone();
    while (true) {
      try {
        // 执行请求，并序列化响应体
        return executeAndDecode(template, options);
      } catch (RetryableException e) {// 只处理重试异常
        // ----------只有抛出RetryException异常才能重试--------------
        try {
          // 决定抛出异常还是重试
          retryer.continueOrPropagate(e);
        } catch (RetryableException th) {
          // 抛出cause还是RetryableException
          Throwable cause = th.getCause();
          if (configuration.getPropagationPolicy() == UNWRAP && cause != null) {
            throw cause;
          } else {
            throw th;
          }
        }
        // 输出重试日志
        if (configuration.getLogLevel() != Logger.Level.NONE) {
          configuration
              .getLogger()
              .logRetry(
                  configuration.getMetadata().configKey(),
                  configuration.getLogLevel());
        }
      }
    }
  }

  /**
   * @param template 模板变量解析后的
   * @param options 可选参数
   */
  Object executeAndDecode(RequestTemplate template, Options options) throws Throwable {
    // 构造请求Request
    Request request = targetRequest(template);
    Logger.Level logLevel = configuration.getLogLevel();
    // 输出Request日志: 按http协议格式输出
    if (logLevel != Logger.Level.NONE) {
      configuration.getLogger().logRequest(
              configuration.getMetadata().configKey(), logLevel, request);
    }
    // 响应体
    Response response;
    long start = System.nanoTime();
    try {
      // 执行请求
      response = client.execute(request, options);
      response = response.toBuilder()
          .request(request)
          .requestTemplate(template).build();
    } catch (IOException e) {
      // 捕获异常，输出错误日志
      if (logLevel != Logger.Level.NONE) {
        configuration.getLogger().logIOException(
                configuration.getMetadata().configKey(),
                logLevel, e, elapsedTime(start));
      }
      // 只有RetryableException异常才会重试
      throw errorExecuting(request, e);
    }
    // ResponseHandler处理响应结果
    long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    // ResponseHandler解码响应体
    return responseHandler.handleResponse(
        configuration.getMetadata().configKey(), response,
        configuration.getMetadata().returnType(), elapsedTime);
  }

  long elapsedTime(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

 private Request targetRequest(RequestTemplate template) {
    // 执行RequestInterceptor拦截逻辑
    for (RequestInterceptor interceptor : configuration.getRequestInterceptors()) {
      interceptor.apply(template);
    }
    // 生成请求
   Target<?> target = configuration.getTarget();
   return target.apply(template);
  }

  /**
   * 优先从参数中取，在从Options#methodOption中获取
   */
  Options findOptions(Object[] argv) {
    // 从options#threadToMethod中获取
    if (argv == null || argv.length == 0) {
      return this.configuration
          .getOptions()
          .getMethodOptions(configuration.getMetadata().method().getName());
    }
    return Stream.of(argv)
        .filter(Options.class::isInstance)
        .map(Options.class::cast)
        .findFirst()
        .orElse(
            this.configuration
                .getOptions()
                .getMethodOptions(configuration.getMetadata().method().getName()));
  }

  static class Factory implements MethodHandler.Factory<Object> {

    private final Client client;
    private final Retryer retryer;
    private final List<RequestInterceptor> requestInterceptors;
    private final ResponseHandler responseHandler;
    private final Logger logger;
    private final Logger.Level logLevel;
    private final ExceptionPropagationPolicy propagationPolicy;
    private final RequestTemplateFactoryResolver requestTemplateFactoryResolver;
    private final Options options;

    Factory(
        Client client,
        Retryer retryer,
        List<RequestInterceptor> requestInterceptors,
        ResponseHandler responseHandler,
        Logger logger,
        Logger.Level logLevel,
        ExceptionPropagationPolicy propagationPolicy,
        RequestTemplateFactoryResolver requestTemplateFactoryResolver,
        Options options) {
      this.client = checkNotNull(client, "client");
      this.retryer = checkNotNull(retryer, "retryer");
      this.requestInterceptors = checkNotNull(requestInterceptors, "requestInterceptors");
      this.responseHandler = checkNotNull(responseHandler, "responseHandler");
      this.logger = checkNotNull(logger, "logger");
      this.logLevel = checkNotNull(logLevel, "logLevel");
      this.propagationPolicy = propagationPolicy;
      this.requestTemplateFactoryResolver =
          checkNotNull(requestTemplateFactoryResolver, "requestTemplateFactoryResolver");
      this.options = checkNotNull(options, "options");
    }

    @Override
    public MethodHandler create(Target<?> target, MethodMetadata md, Object requestContext) {
      // RequestTemplate解析工厂类，通过将MethodMetadata.requestTemplate和方法参数，解析成一个可请求RequestTemplate
      // RequestTemplateFactoryResolver根据方法元数据生成RequestTemplate.Factory
      final RequestTemplate.Factory requestTemplateFactory = requestTemplateFactoryResolver.resolve(target, md);
      // MethodHandler配置类
      MethodHandlerConfiguration methodHandlerConfiguration =
          new MethodHandlerConfiguration(
              md,
              target,
              retryer,
              requestInterceptors,
              logger,
              logLevel,
              requestTemplateFactory,
              options,
              propagationPolicy);
      // 创建SynchronousMethodHandler
      return new SynchronousMethodHandler(methodHandlerConfiguration, client, responseHandler);
    }
  }
}

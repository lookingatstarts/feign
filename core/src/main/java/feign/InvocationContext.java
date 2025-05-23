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

import static feign.FeignException.errorReading;
import static feign.Util.ensureClosed;

import feign.codec.DecodeException;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.lang.reflect.Type;

public class InvocationContext {
  private static final long MAX_RESPONSE_BUFFER_SIZE = 8192L;
  private final String configKey;
  // 解码器
  private final Decoder decoder;
  // 错误解码器
  private final ErrorDecoder errorDecoder;
  // 默认false
  private final boolean dismiss404;
  // 默认true
  private final boolean closeAfterDecode;
  // 默认false
  private final boolean decodeVoid;
  private final Response response;
  private final Type returnType;

  InvocationContext(
      String configKey,
      Decoder decoder,
      ErrorDecoder errorDecoder,
      boolean dismiss404,
      boolean closeAfterDecode,
      boolean decodeVoid,
      Response response,
      Type returnType) {
    this.configKey = configKey;
    this.decoder = decoder;
    this.errorDecoder = errorDecoder;
    this.dismiss404 = dismiss404;
    this.closeAfterDecode = closeAfterDecode;
    this.decodeVoid = decodeVoid;
    this.response = response;
    this.returnType = returnType;
  }

  public Decoder decoder() {
    return decoder;
  }

  public Type returnType() {
    return returnType;
  }

  public Response response() {
    return response;
  }

  /**
   * 没有配置ResponseInterceptor，或者配置的ResponseInterceptor处理不了
   * 如果Chain使用的方式为Default，就会调用proceed方法
   */
  public Object proceed() throws Exception {
    if (returnType == Response.class) {
      // 数据小于MAX_RESPONSE_BUFFER_SIZE，流会被关闭
      return disconnectResponseBodyIfNeeded(response);
    }
    try {
      // 是否反序列化响应体
      final boolean shouldDecodeResponseBody =
          (response.status() >= 200 && response.status() < 300)
              || (response.status() == 404 && dismiss404 && !isVoidType(returnType));
      // 接口请求失败，ErrorDecode解码异常，
      if (!shouldDecodeResponseBody) {
        throw decodeError(configKey, response);
      }
      // 如果返回为void是否反序列化
      if (isVoidType(returnType) && !decodeVoid) {
        ensureClosed(response.body());
        return null;
      }
      // 如果是TypedResponse
      Class<?> rawType = Types.getRawType(returnType);
      if (TypedResponse.class.isAssignableFrom(rawType)) {
        Type bodyType = Types.resolveLastTypeParameter(returnType, TypedResponse.class);
        return TypedResponse.builder(response).body(decode(response, bodyType)).build();
      }
      // 解码
      return decode(response, returnType);
    } finally {
      // 关闭流
      if (closeAfterDecode) {
        ensureClosed(response.body());
      }
    }
  }

  /**
   * 读取数据后关闭流
   */
  private static Response disconnectResponseBodyIfNeeded(Response response) throws IOException {
    final boolean shouldDisconnectResponseBody =
        response.body() != null
            && response.body().length() != null
            && response.body().length() <= MAX_RESPONSE_BUFFER_SIZE;
    if (!shouldDisconnectResponseBody) {
      return response;
    }
    try {
      // Util.toByteArray会关闭inputStream流
      final byte[] bodyData = Util.toByteArray(response.body().asInputStream());
      return response.toBuilder().body(bodyData).build();
    } finally {
      // 关闭流：response.body
      ensureClosed(response.body());
    }
  }

  /**
   * 解析失败抛出异常
   */
  private Object decode(Response response, Type returnType) {
    try {
      return decoder.decode(response, returnType);
    } catch (final FeignException e) {
      throw e;
    } catch (final RuntimeException e) {
      throw new DecodeException(response.status(), e.getMessage(), response.request(), e);
    } catch (IOException e) {
      throw errorReading(response.request(), response, e);
    }
  }

  private Exception decodeError(String methodKey, Response response) {
    try {
      return errorDecoder.decode(methodKey, response);
    } finally {
      // 关闭流
      ensureClosed(response.body());
    }
  }

  private boolean isVoidType(Type returnType) {
    return returnType == Void.class
        || returnType == void.class
        || returnType.getTypeName().equals("kotlin.Unit");
  }
}

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

import static feign.Util.*;

import feign.Request.ProtocolVersion;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 不可修改
 * status: 状态码
 * reason: http协议原语
 * headers：响应头
 * body: 响应体
 * request: 请求
 * protocolVersion: http协议版本
 */

/** An immutable response to an http invocation which only returns string content. */
public final class Response implements Closeable {
  // http响应码
  private final int status;
  private final String reason;
  // 协议版本
  private final ProtocolVersion protocolVersion;
  // 响应头
  private final Map<String, Collection<String>> headers;
  // 响应体
  private final Body body;
  // 请求
  private final Request request;
  private RequestTemplate requestTemplate;

  private Response(Builder builder) {
    checkState(builder.request != null, "original request is required");
    this.status = builder.status;
    this.request = builder.request;
    this.reason = builder.reason; // nullable
    this.headers = caseInsensitiveCopyOf(builder.headers);
    this.body = builder.body; // nullable
    this.protocolVersion = builder.protocolVersion;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private static final ProtocolVersion DEFAULT_PROTOCOL_VERSION = ProtocolVersion.HTTP_1_1;
    int status;
    String reason;
    Map<String, Collection<String>> headers;
    /**
     * 响应体实现
     * ByteArrayBody
     * InputStreamBody
     */
    Body body;
    Request request;
    private ProtocolVersion protocolVersion = DEFAULT_PROTOCOL_VERSION;
    RequestTemplate requestTemplate;

    Builder() {}

    /**
     * 从response创建Builder
     */
    Builder(Response source) {
      this.status = source.status;
      this.reason = source.reason;
      this.headers = source.headers;
      this.body = source.body;
      this.request = source.request;
      this.protocolVersion = source.protocolVersion;
    }

    /**
     * @see Response#status
     */
    public Builder status(int status) {
      this.status = status;
      return this;
    }

    /**
     * @see Response#reason
     */
    public Builder reason(String reason) {
      this.reason = reason;
      return this;
    }

    /**
     * @see Response#headers
     */
    public Builder headers(Map<String, Collection<String>> headers) {
      this.headers = headers;
      return this;
    }

    /**
     * @see Response#body
     */
    public Builder body(Body body) {
      this.body = body;
      return this;
    }

    /**
     * @see Response#body
     */
    public Builder body(InputStream inputStream, Integer length) {
      this.body = InputStreamBody.orNull(inputStream, length);
      return this;
    }

    /**
     * @see Response#body
     */
    public Builder body(byte[] data) {
      this.body = ByteArrayBody.orNull(data);
      return this;
    }

    /**
     * @see Response#body
     */
    public Builder body(String text, Charset charset) {
      this.body = ByteArrayBody.orNull(text, charset);
      return this;
    }

    /**
     * @see Response#request
     */
    public Builder request(Request request) {
      checkNotNull(request, "request is required");
      this.request = request;
      return this;
    }

    /** HTTP protocol version */
    public Builder protocolVersion(ProtocolVersion protocolVersion) {
      this.protocolVersion = (protocolVersion != null) ? protocolVersion : DEFAULT_PROTOCOL_VERSION;
      return this;
    }

    /**
     * The Request Template used for the original request.
     *
     * @param requestTemplate used.
     * @return builder reference.
     */
    @Experimental
    public Builder requestTemplate(RequestTemplate requestTemplate) {
      this.requestTemplate = requestTemplate;
      return this;
    }

    public Response build() {
      return new Response(this);
    }
  }

  /**
   * status code. ex {@code 200}
   *
   * <p>See <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html" >rfc2616</a>
   */
  public int status() {
    return status;
  }

  /**
   * Nullable and not set when using http/2 See <a
   * href="https://github.com/http2/http2-spec/issues/202">...</a> See <a
   * href="https://github.com/http2/http2-spec/issues/202">...</a>
   */
  public String reason() {
    return reason;
  }

  /** Returns a case-insensitive mapping of header names to their values. */
  public Map<String, Collection<String>> headers() {
    return headers;
  }

  /** if present, the response had a body */
  public Body body() {
    return body;
  }

  /** the request that generated this response */
  public Request request() {
    return request;
  }

  /**
   * the HTTP protocol version
   *
   * @return HTTP protocol version or empty if a client does not provide it
   */
  public ProtocolVersion protocolVersion() {
    return protocolVersion;
  }

  /**
   * Returns a charset object based on the requests content type. Defaults to UTF-8 See <a
   * href="https://datatracker.ietf.org/doc/html/rfc7231#section-5.3.3">rfc7231 - Accept-Charset</a>
   * See <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-3.1.1.1">rfc7231 - Media
   * Type</a>
   */
  public Charset charset() {

    Collection<String> contentTypeHeaders = headers().get("Content-Type");

    if (contentTypeHeaders != null) {
      for (String contentTypeHeader : contentTypeHeaders) {
        String[] contentTypeParmeters = contentTypeHeader.split(";");
        if (contentTypeParmeters.length > 1) {
          String[] charsetParts = contentTypeParmeters[1].split("=");
          if (charsetParts.length == 2 && "charset".equalsIgnoreCase(charsetParts[0].trim())) {
            String charsetString = charsetParts[1].replaceAll("\"", "");
            return Charset.forName(charsetString);
          }
        }
      }
    }

    return Util.UTF_8;
  }

  @Override
  public String toString() {
    StringBuilder builder =
        new StringBuilder(protocolVersion.toString()).append(" ").append(status);
    if (reason != null) builder.append(' ').append(reason);
    builder.append('\n');
    for (String field : headers.keySet()) {
      for (String value : valuesOrEmpty(headers, field)) {
        builder.append(field).append(": ").append(value).append('\n');
      }
    }
    if (body != null) builder.append('\n').append(body);
    return builder.toString();
  }

  /**
   * Response关闭就是关闭body
   */
  @Override
  public void close() {
    Util.ensureClosed(body);
  }

  /**
   * 响应体，需要关闭的资源
   * 1、大小 2、是否可重复读 3、asInputStream 4、asReader 5、关闭资源
   */
  public interface Body extends Closeable {

    /**
     * body的大小
     * length in bytes, if known. Null if unknown or greater than {@link Integer#MAX_VALUE}. <br>
     * <br>
     * <br>
     * <b>Note</b><br>
     * This is an integer as most implementations cannot do bodies greater than 2GB.
     */
    Integer length();

    /**
     * 如果为true,asInputStream asReader可以读取多次
     * True if {@link #asInputStream()} and {@link #asReader()} can be called more than once.
     * */
    boolean isRepeatable();

    /**
     * 调用者需要调用close进行关闭
     */
    /** It is the responsibility of the caller to close the stream. */
    InputStream asInputStream() throws IOException;

    /**
     * 调用者需要调用close进行关闭
     */
    /**
     * It is the responsibility of the caller to close the stream.
     *
     * @deprecated favor {@link Body#asReader(Charset)}
     */
    @Deprecated
    default Reader asReader() throws IOException {
      return asReader(StandardCharsets.UTF_8);
    }

    /** It is the responsibility of the caller to close the stream. */
    Reader asReader(Charset charset) throws IOException;
  }

  /**
   * InputStream
   */
  private static final class InputStreamBody implements Response.Body {

    private final InputStream inputStream;
    private final Integer length;

    private InputStreamBody(InputStream inputStream, Integer length) {
      this.inputStream = inputStream;
      this.length = length;
    }

    private static Body orNull(InputStream inputStream, Integer length) {
      if (inputStream == null) {
        return null;
      }
      return new InputStreamBody(inputStream, length);
    }

    @Override
    public Integer length() {
      return length;
    }

    @Override
    public boolean isRepeatable() {
      return false;
    }

    @Override
    public InputStream asInputStream() {
      return inputStream;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Reader asReader() {
      return new InputStreamReader(inputStream, UTF_8);
    }

    @Override
    public Reader asReader(Charset charset) {
      checkNotNull(charset, "charset should not be null");
      return new InputStreamReader(inputStream, charset);
    }

    @Override
    public void close() throws IOException {
      inputStream.close();
    }
  }

  /**
   * 将数据保存到ByteArray中，支持重复读
   */
  private static final class ByteArrayBody implements Response.Body {

    private final byte[] data;

    public ByteArrayBody(byte[] data) {
      this.data = data;
    }

    private static Body orNull(byte[] data) {
      if (data == null) {
        return null;
      }
      return new ByteArrayBody(data);
    }

    private static Body orNull(String text, Charset charset) {
      if (text == null) {
        return null;
      }
      checkNotNull(charset, "charset");
      return new ByteArrayBody(text.getBytes(charset));
    }

    @Override
    public Integer length() {
      return data.length;
    }

    @Override
    public boolean isRepeatable() {
      return true;
    }

    @Override
    public InputStream asInputStream() {
      return new ByteArrayInputStream(data);
    }

    @SuppressWarnings("deprecation")
    @Override
    public Reader asReader() {
      return new InputStreamReader(asInputStream(), UTF_8);
    }

    @Override
    public Reader asReader(Charset charset) {
      checkNotNull(charset, "charset should not be null");
      return new InputStreamReader(asInputStream(), charset);
    }

    @Override
    public void close() {}
  }
}

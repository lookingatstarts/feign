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
import static feign.Util.getThreadIdentifier;
import static feign.Util.valuesOrEmpty;

import java.io.Serializable;
import java.net.HttpURLConnection;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 一个不可修改的请求
 */
/** An immutable request to an http server. */
public final class Request implements Serializable {

  public enum HttpMethod {
    GET,
    HEAD,
    POST(true),
    PUT(true),
    DELETE,
    CONNECT,
    OPTIONS,
    TRACE,
    PATCH(true);

    private final boolean withBody;

    HttpMethod() {
      this(false);
    }

    HttpMethod(boolean withBody) {
      this.withBody = withBody;
    }

    public boolean isWithBody() {
      return this.withBody;
    }
  }

  public enum ProtocolVersion {
    HTTP_1_0("HTTP/1.0"),
    HTTP_1_1("HTTP/1.1"),
    HTTP_2("HTTP/2.0"),
    MOCK;

    final String protocolVersion;

    ProtocolVersion() {
      protocolVersion = name();
    }

    ProtocolVersion(String protocolVersion) {
      this.protocolVersion = protocolVersion;
    }

    @Override
    public String toString() {
      return protocolVersion;
    }
  }

  /**
   * No parameters can be null except {@code body} and {@code charset}. All parameters must be
   * effectively immutable, via safe copies, not mutating or otherwise.
   *
   * @deprecated {@link #create(HttpMethod, String, Map, byte[], Charset)}
   */
  @Deprecated
  public static Request create(
      String method,
      String url,
      Map<String, Collection<String>> headers,
      byte[] body,
      Charset charset) {
    checkNotNull(method, "httpMethod of %s", method);
    final HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase());
    return create(httpMethod, url, headers, body, charset, null);
  }

  /**
   * Builds a Request. All parameters must be effectively immutable, via safe copies.
   *
   * @param httpMethod for the request.
   * @param url for the request.
   * @param headers to include.
   * @param body of the request, can be {@literal null}
   * @param charset of the request, can be {@literal null}
   * @return a Request
   */
  @Deprecated
  public static Request create(
      HttpMethod httpMethod,
      String url,
      Map<String, Collection<String>> headers,
      byte[] body,
      Charset charset) {
    return create(httpMethod, url, headers, Body.create(body, charset), null);
  }

  /**
   * Builds a Request. All parameters must be effectively immutable, via safe copies.
   *
   * @param httpMethod for the request.
   * @param url for the request.
   * @param headers to include.
   * @param body of the request, can be {@literal null}
   * @param charset of the request, can be {@literal null}
   * @return a Request
   */
  public static Request create(
      HttpMethod httpMethod,
      String url,
      Map<String, Collection<String>> headers,
      byte[] body,
      Charset charset,
      RequestTemplate requestTemplate) {
    return create(httpMethod, url, headers, Body.create(body, charset), requestTemplate);
  }

  /**
   * Builds a Request. All parameters must be effectively immutable, via safe copies.
   *
   * @param httpMethod for the request.
   * @param url for the request.
   * @param headers to include.
   * @param body of the request, can be {@literal null}
   * @return a Request
   */
  public static Request create(
      HttpMethod httpMethod,
      String url,
      Map<String, Collection<String>> headers,
      Body body,
      RequestTemplate requestTemplate) {
    return new Request(httpMethod, url, headers, body, requestTemplate);
  }

  // 请求方式
  private final HttpMethod httpMethod;
  // 请求url
  private final String url;
  // 请求头
  private final Map<String, Collection<String>> headers;
  // 请求体
  private final Body body;
  // 请求模版
  private final RequestTemplate requestTemplate;
  // 请求协议
  private final ProtocolVersion protocolVersion;

  /**
   * Creates a new Request.
   *
   * @param method of the request.
   * @param url for the request.
   * @param headers for the request.
   * @param body for the request, optional.
   * @param requestTemplate used to build the request.
   */
  Request(
      HttpMethod method,
      String url,
      Map<String, Collection<String>> headers,
      Body body,
      RequestTemplate requestTemplate) {
    this.httpMethod = checkNotNull(method, "httpMethod of %s", method.name());
    this.url = checkNotNull(url, "url");
    this.headers = checkNotNull(headers, "headers of %s %s", method, url);
    this.body = body;
    this.requestTemplate = requestTemplate;
    protocolVersion = ProtocolVersion.HTTP_1_1;
  }

  /**
   * Http Method for this request.
   *
   * @return the HttpMethod string
   * @deprecated @see {@link #httpMethod()}
   */
  @Deprecated
  public String method() {
    return httpMethod.name();
  }

  /**
   * Http Method for the request.
   *
   * @return the HttpMethod.
   */
  public HttpMethod httpMethod() {
    return this.httpMethod;
  }

  /**
   * URL for the request.
   *
   * @return URL as a String.
   */
  public String url() {
    return url;
  }

  /**
   * Request Headers.
   *
   * @return the request headers.
   */
  public Map<String, Collection<String>> headers() {
    return Collections.unmodifiableMap(headers);
  }

  /**
   * Add new entries to request Headers. It overrides existing entries
   *
   * @param key
   * @param value
   */
  public void header(String key, String value) {
    header(key, Arrays.asList(value));
  }

  /**
   * Add new entries to request Headers. It overrides existing entries
   *
   * @param key
   * @param values
   */
  public void header(String key, Collection<String> values) {
    headers.put(key, values);
  }

  /**
   * Charset of the request.
   *
   * @return the current character set for the request, may be {@literal null} for binary data.
   */
  public Charset charset() {
    return body.encoding;
  }

  /**
   * If present, this is the replayable body to send to the server. In some cases, this may be
   * interpretable as text.
   *
   * @see #charset()
   */
  public byte[] body() {
    return body.data;
  }

  /**
   * 是否为二进制数据
   */
  public boolean isBinary() {
    return body.isBinary();
  }

  /**
   * Request Length.
   *
   * @return size of the request body.
   */
  public int length() {
    return this.body.length();
  }

  /**
   * Request HTTP protocol version
   *
   * @return HTTP protocol version
   */
  public ProtocolVersion protocolVersion() {
    return protocolVersion;
  }

  /**
   * Request as an HTTP/1.1 request.
   * 按照http协议格式输出
   * @return the request.
   */
  @Override
  public String toString() {
    final StringBuilder builder = new StringBuilder();
    builder
        .append(httpMethod)
        .append(' ')
        .append(url)
        .append(' ')
        .append(protocolVersion)
        .append('\n');
    for (final String field : headers.keySet()) {
      for (final String value : valuesOrEmpty(headers, field)) {
        builder.append(field).append(": ").append(value).append('\n');
      }
    }
    if (body != null) {
      builder.append('\n').append(body.asString());
    }
    return builder.toString();
  }

  /**
   * Controls the per-request settings currently required to be implemented by all {@link Client
   * clients}
   * 可选参数
   */
  public static class Options {

    // 连接时长
    private final long connectTimeout;
    private final TimeUnit connectTimeoutUnit;
    // 超时时间
    private final long readTimeout;
    private final TimeUnit readTimeoutUnit;
    private final boolean followRedirects;
    // threadGroup+threadName+threadId合并
    private final Map<String, Map<String, Options>> threadToMethodOptions;

    /**
     * Get an Options by methodName
     *
     * @param methodName it's your FeignInterface method name.
     * @return method Options
     */
    @Experimental
    public Options getMethodOptions(String methodName) {
      Map<String, Options> methodOptions =
          threadToMethodOptions.getOrDefault(getThreadIdentifier(), new HashMap<>());
      return methodOptions.getOrDefault(methodName, this);
    }

    /**
     * Set methodOptions by methodKey and options
     *
     * @param methodName it's your FeignInterface method name.
     * @param options it's the Options for this method.
     */
    @Experimental
    public void setMethodOptions(String methodName, Options options) {
      String threadIdentifier = getThreadIdentifier();
      Map<String, Request.Options> methodOptions =
          threadToMethodOptions.getOrDefault(threadIdentifier, new HashMap<>());
      threadToMethodOptions.put(threadIdentifier, methodOptions);
      methodOptions.put(methodName, options);
    }

    /**
     * Creates a new Options instance.
     *
     * @param connectTimeoutMillis connection timeout in milliseconds.
     * @param readTimeoutMillis read timeout in milliseconds.
     * @param followRedirects if the request should follow 3xx redirections.
     * @deprecated please use {@link #Options(long, TimeUnit, long, TimeUnit, boolean)}
     */
    @Deprecated
    public Options(int connectTimeoutMillis, int readTimeoutMillis, boolean followRedirects) {
      this(
          connectTimeoutMillis,
          TimeUnit.MILLISECONDS,
          readTimeoutMillis,
          TimeUnit.MILLISECONDS,
          followRedirects);
    }

    /**
     * Creates a new Options Instance.
     *
     * @param connectTimeout value.
     * @param connectTimeoutUnit with the TimeUnit for the timeout value.
     * @param readTimeout value.
     * @param readTimeoutUnit with the TimeUnit for the timeout value.
     * @param followRedirects if the request should follow 3xx redirections. 是否支持重定向
     */
    public Options(
        long connectTimeout,
        TimeUnit connectTimeoutUnit,
        long readTimeout,
        TimeUnit readTimeoutUnit,
        boolean followRedirects) {
      super();
      this.connectTimeout = connectTimeout;
      this.connectTimeoutUnit = connectTimeoutUnit;
      this.readTimeout = readTimeout;
      this.readTimeoutUnit = readTimeoutUnit;
      this.followRedirects = followRedirects;
      this.threadToMethodOptions = new ConcurrentHashMap<>();
    }

    /**
     * Creates a new Options instance that follows redirects by default.
     *
     * @param connectTimeoutMillis connection timeout in milliseconds.
     * @param readTimeoutMillis read timeout in milliseconds.
     * @deprecated please use {@link #Options(long, TimeUnit, long, TimeUnit, boolean)}
     */
    @Deprecated
    public Options(int connectTimeoutMillis, int readTimeoutMillis) {
      this(connectTimeoutMillis, readTimeoutMillis, true);
    }

    /**
     * Creates a new Options Instance.
     *
     * @param connectTimeout value.
     * @param readTimeout value.
     * @param followRedirects if the request should follow 3xx redirections.
     */
    public Options(Duration connectTimeout, Duration readTimeout, boolean followRedirects) {
      this(
          connectTimeout.toMillis(),
          TimeUnit.MILLISECONDS,
          readTimeout.toMillis(),
          TimeUnit.MILLISECONDS,
          followRedirects);
    }

    /**
     * Creates the new Options instance using the following defaults:
     *
     * <ul>
     *   <li>Connect Timeout: 10 seconds
     *   <li>Read Timeout: 60 seconds
     *   <li>Follow all 3xx redirects
     * </ul>
     */
    public Options() {
      this(10, TimeUnit.SECONDS, 60, TimeUnit.SECONDS, true);
    }

    /**
     * Defaults to 10 seconds. {@code 0} implies no timeout.
     *
     * @see java.net.HttpURLConnection#getConnectTimeout()
     */
    public int connectTimeoutMillis() {
      return (int) connectTimeoutUnit.toMillis(connectTimeout);
    }

    /**
     * Defaults to 60 seconds. {@code 0} implies no timeout.
     *
     * @see java.net.HttpURLConnection#getReadTimeout()
     */
    public int readTimeoutMillis() {
      return (int) readTimeoutUnit.toMillis(readTimeout);
    }

    /**
     * Defaults to true. {@code false} tells the client to not follow the redirections.
     *
     * @see HttpURLConnection#getFollowRedirects()
     */
    public boolean isFollowRedirects() {
      return followRedirects;
    }

    /**
     * Connect Timeout Value.
     *
     * @return current timeout value.
     */
    public long connectTimeout() {
      return connectTimeout;
    }

    /**
     * TimeUnit for the Connection Timeout value.
     *
     * @return TimeUnit
     */
    public TimeUnit connectTimeoutUnit() {
      return connectTimeoutUnit;
    }

    /**
     * Read Timeout value.
     *
     * @return current read timeout value.
     */
    public long readTimeout() {
      return readTimeout;
    }

    /**
     * TimeUnit for the Read Timeout value.
     *
     * @return TimeUnit
     */
    public TimeUnit readTimeoutUnit() {
      return readTimeoutUnit;
    }
  }

  @Experimental
  public RequestTemplate requestTemplate() {
    return this.requestTemplate;
  }

  /**
   * Request Body：byte[] + charset
   *
   * <p>Considered experimental, will most likely be made internal going forward.
   */
  @Experimental
  public static class Body implements Serializable {

    private transient Charset encoding;
    private byte[] data;

    private Body() {
      super();
    }

    private Body(byte[] data) {
      this.data = data;
    }

    private Body(byte[] data, Charset encoding) {
      this.data = data;
      this.encoding = encoding;
    }

    public Optional<Charset> getEncoding() {
      return Optional.ofNullable(this.encoding);
    }

    public int length() {
      /* calculate the content length based on the data provided */
      return data != null ? data.length : 0;
    }

    public byte[] asBytes() {
      return data;
    }

    public String asString() {
      return !isBinary() ? new String(data, encoding) : "Binary data";
    }

    // 是否为二进制数据
    public boolean isBinary() {
      return encoding == null || data == null;
    }

    public static Body create(String data) {
      return new Body(data.getBytes());
    }

    public static Body create(String data, Charset charset) {
      return new Body(data.getBytes(charset), charset);
    }

    public static Body create(byte[] data) {
      return new Body(data);
    }

    public static Body create(byte[] data, Charset charset) {
      return new Body(data, charset);
    }

    /**
     * Creates a new Request Body with charset encoded data.
     *
     * @param data to be encoded.
     * @param charset to encode the data with. if {@literal null}, then data will be considered
     *     binary and will not be encoded.
     * @return a new Request.Body instance with the encoded data.
     * @deprecated please use {@link Request.Body#create(byte[], Charset)}
     */
    @Deprecated
    public static Body encoded(byte[] data, Charset charset) {
      return create(data, charset);
    }

    public static Body empty() {
      return new Body();
    }
  }
}

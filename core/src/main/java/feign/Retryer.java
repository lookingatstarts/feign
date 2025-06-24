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

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * 重试器
 * Cloned for each invocation to {@link Client#execute(Request, feign.Request.Options)}.
 * Implementations may keep state to determine if retry operations should continue or not.
 */
public interface Retryer extends Cloneable {

  /**
   * 如果允许重试，则return或者阻塞一段时间，否则抛出异常
   * if retry is permitted, return (possibly after sleeping). Otherwise, propagate the exception.
   */
  void continueOrPropagate(RetryableException e);

  Retryer clone();

  /**
   * 默认实现：可重试次数内，间隔的请求，同时不超过最大重试时间
   */
  class Default implements Retryer {

    /**
     * 最大重试次数
     */
    private final int maxAttempts;
    /**
     * 重试间隔ms
     */
    private final long period;
    /**
     * 最大重试间隔ms
     */
    private final long maxPeriod;
    /**
     * 已重试次数
     */
    int attempt;
    /**
     * 总阻塞时长
     */
    long sleptForMillis;

    public Default() {
      this(100, SECONDS.toMillis(1), 5);
    }

    public Default(long period, long maxPeriod, int maxAttempts) {
      this.period = period;
      this.maxPeriod = maxPeriod;
      this.maxAttempts = maxAttempts;
      this.attempt = 1;
    }

    // visible for testing;
    protected long currentTimeMillis() {
      return System.currentTimeMillis();
    }

    public void continueOrPropagate(RetryableException e) {
      // 重试次数+1 >=最大重试次数 抛出原异常
      if (attempt++ >= maxAttempts) {
        throw e;
      }
      long interval;
      // 服务端返回Retry-After响应头
      if (e.retryAfter() != null) {
        interval = e.retryAfter() - currentTimeMillis();
        if (interval > maxPeriod) {
          interval = maxPeriod;
        }
        if (interval < 0) {
          return;
        }
      } else {
        interval = nextMaxInterval();
      }
      try {
        Thread.sleep(interval);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
        throw e;
      }
      sleptForMillis += interval;
    }

    /**
     * Calculates the time interval to a retry attempt.<br>
     * The interval increases exponentially with each attempt, at a rate of nextInterval *= 1.5
     * (where 1.5 is the backoff factor), to the maximum interval.
     *
     * @return time in milliseconds from now until the next attempt.
     */
    long nextMaxInterval() {
      long interval = (long) (period * Math.pow(1.5, attempt - 1));
      return Math.min(interval, maxPeriod);
    }

    @Override
    public Retryer clone() {
      return new Default(period, maxPeriod, maxAttempts);
    }
  }

  /**
   * 不重试
   * Implementation that never retries request. It propagates the RetryableException.
   * */
  Retryer NEVER_RETRY =
      new Retryer() {

        /**
         * 抛出异常，不重试
         */
        @Override
        public void continueOrPropagate(RetryableException e) {
          throw e;
        }

        @Override
        public Retryer clone() {
          return this;
        }
      };
}

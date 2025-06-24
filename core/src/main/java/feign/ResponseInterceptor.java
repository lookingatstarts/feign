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

public interface ResponseInterceptor {

  /**
   * 如果要将InvocationContext往下传，就必须调用Chain.next
   * 1、如果不想别的interceptor执行了，就不用chain.next(InvocationContext)
   */
  Object intercept(InvocationContext invocationContext, Chain chain) throws Exception;

  /**
   *ResponseInterceptor c = A.andThen(B)
   * 返回一个新的对象C,C调用intercept时，先执行A，在执行B
   */
  default ResponseInterceptor andThen(ResponseInterceptor nextInterceptor) {
    return (ic, chain) ->{
      // 将B封装成Chain
       Chain newChain = nextContext -> nextInterceptor.intercept(nextContext, chain);
     return this.intercept(ic, newChain);
    };
  }

  /**
   * responseInterceptorA.apply(ChainB)
   * 返回一个新的Chain,先执行A的intercept，如果请求InvoiceContext继续往下传，就调用chainB#next
   */
  default Chain apply(Chain chain) {
    return invocationContext -> this.intercept(invocationContext, chain);
  }

  /***
   * 责任链设计模式
   */
  interface Chain {

    // 默认实现
    Chain DEFAULT = InvocationContext::proceed;

    Object next(InvocationContext context) throws Exception;
  }
}

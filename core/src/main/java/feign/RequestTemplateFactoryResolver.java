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

import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;

import feign.codec.EncodeException;
import feign.codec.Encoder;
import feign.template.UriUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RequestTemplateFactory解析器，根据methodMetadata模版中是否有queryMap body选择
 * 不同RequestTemplateFactory实现
 */
final class RequestTemplateFactoryResolver {
  private final Encoder encoder;
  private final QueryMapEncoder queryMapEncoder;

  RequestTemplateFactoryResolver(Encoder encoder, QueryMapEncoder queryMapEncoder) {
    this.encoder = checkNotNull(encoder, "encoder");
    this.queryMapEncoder = checkNotNull(queryMapEncoder, "queryMapEncoder");
  }

  /**
   * 获取RequestTemplate.Factory
   */
  public RequestTemplate.Factory resolve(Target<?> target, MethodMetadata md) {
    if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {
      // 处理表单数据
      return new BuildFormEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
    } else if (md.bodyIndex() != null || md.alwaysEncodeBody()) {
      // 处理响应体
      return new BuildEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
    } else {
      return new BuildTemplateByResolvingArgs(md, queryMapEncoder, target);
    }
  }

  /**
   *  RequestTemplate create(Object[] args)
   *  工厂类
   *  BuildTemplateByResolvingArgs 解析模板变量
   */
  private static class BuildTemplateByResolvingArgs implements RequestTemplate.Factory {

    private final QueryMapEncoder queryMapEncoder;
    protected final MethodMetadata metadata;
    protected final Target<?> target;
    /**
     * 通过@Param注释的参数
     */
    private final Map<Integer, Param.Expander> indexToExpander = new LinkedHashMap<Integer, Param.Expander>();

    private BuildTemplateByResolvingArgs(
        MethodMetadata metadata, QueryMapEncoder queryMapEncoder, Target<?> target) {
      this.metadata = metadata;
      this.target = target;
      this.queryMapEncoder = queryMapEncoder;
      if (metadata.indexToExpander() != null) {
        indexToExpander.putAll(metadata.indexToExpander());
        return;
      }
      if (metadata.indexToExpanderClass().isEmpty()) {
        return;
      }
      for (Map.Entry<Integer, Class<? extends Param.Expander>> indexToExpanderClass :
          metadata.indexToExpanderClass().entrySet()) {
        try {
          indexToExpander.put(
              indexToExpanderClass.getKey(), indexToExpanderClass.getValue().newInstance());
        } catch (InstantiationException |IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }

    @Override
    public RequestTemplate create(Object[] argv) {
      // 复制RequestTemplate
      RequestTemplate mutable = RequestTemplate.from(metadata.template());
      mutable.feignTarget(target);
      // 处理URL参数
      if (metadata.urlIndex() != null) {
        int urlIndex = metadata.urlIndex();
        checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
        mutable.target(String.valueOf(argv[urlIndex]));
      }
      // Expander处理参数
      Map<String, Object> varBuilder = new LinkedHashMap<String, Object>();
      for (Map.Entry<Integer, Collection<String>> entry : metadata.indexToName().entrySet()) {
        int i = entry.getKey();
        Object value = argv[entry.getKey()];
        if (value != null) { // Null values are skipped.
          if (indexToExpander.containsKey(i)) {
            // 如果参数是Iterable则会分别处理
            value = expandElements(indexToExpander.get(i), value);
          }
          for (String name : entry.getValue()) {
            varBuilder.put(name, value);
          }
        }
      }
      // 将参数替换模板变量：uriTemplate headersTemplate queriesTemplate bodyTemplate
      RequestTemplate template = resolve(argv, mutable, varBuilder);
      // 最多只能一个queryMap todo
      if (metadata.queryMapIndex() != null) {
        Object value = argv[metadata.queryMapIndex()];
        Map<String, Object> queryMap = toQueryMap(value, metadata.queryMapEncoder());
        addQueryMapQueryParameters(queryMap, template);
      }
      // 最多只有一个headMap todo
      if (metadata.headerMapIndex() != null) {
        // add header map parameters for a resolution of the user pojo object
        Object value = argv[metadata.headerMapIndex()];
        Map<String, Object> headerMap = toQueryMap(value, metadata.queryMapEncoder());
        addHeaderMapHeaders(headerMap, template);
      }
      return template;
    }

    private Map<String, Object> toQueryMap(Object value, QueryMapEncoder queryMapEncoder) {
      // 直接返回
      if (value instanceof Map) {
        return (Map<String, Object>) value;
      }
      try {
        // 使用QueryMapEncoder对value进行encode
        // encode with @QueryMap annotation if exists otherwise with the one from this resolver
        return queryMapEncoder != null
            ? queryMapEncoder.encode(value)
            : this.queryMapEncoder.encode(value);
      } catch (EncodeException e) {
        throw new IllegalStateException(e);
      }
    }

    private Object expandElements(Param.Expander expander, Object value) {
      if (value instanceof Iterable) {
        return expandIterable(expander, (Iterable) value);
      }
      return expander.expand(value);
    }

    private List<String> expandIterable(Param.Expander expander, Iterable value) {
      List<String> values = new ArrayList<String>();
      for (Object element : value) {
        if (element != null) {
          values.add(expander.expand(element));
        }
      }
      return values;
    }

    @SuppressWarnings("unchecked")
    private void addHeaderMapHeaders(
        Map<String, Object> headerMap, RequestTemplate mutable) {
      for (Map.Entry<String, Object> currEntry : headerMap.entrySet()) {
        Collection<String> values = new ArrayList<String>();

        Object currValue = currEntry.getValue();
        if (currValue instanceof Iterable<?>) {
          Iterator<?> iter = ((Iterable<?>) currValue).iterator();
          while (iter.hasNext()) {
            Object nextObject = iter.next();
            values.add(nextObject == null ? null : nextObject.toString());
          }
        } else {
          values.add(currValue == null ? null : currValue.toString());
        }

        mutable.header(currEntry.getKey(), values);
      }
    }

    @SuppressWarnings("unchecked")
    private void addQueryMapQueryParameters(
        Map<String, Object> queryMap, RequestTemplate mutable) {
      for (Map.Entry<String, Object> currEntry : queryMap.entrySet()) {
        Collection<String> values = new ArrayList<String>();

        Object currValue = currEntry.getValue();
        if (currValue instanceof Iterable<?>) {
          Iterator<?> iter = ((Iterable<?>) currValue).iterator();
          while (iter.hasNext()) {
            Object nextObject = iter.next();
            values.add(nextObject == null ? null : UriUtils.encode(nextObject.toString()));
          }
        } else if (currValue instanceof Object[]) {
          for (Object value : (Object[]) currValue) {
            values.add(value == null ? null : UriUtils.encode(value.toString()));
          }
        } else {
          if (currValue != null) {
            values.add(UriUtils.encode(currValue.toString()));
          }
        }
        if (values.size() > 0) {
          mutable.query(UriUtils.encode(currEntry.getKey()), values);
        }
      }
    }

    protected RequestTemplate resolve(
        Object[] argv, RequestTemplate mutable, Map<String, Object> variables) {
      return mutable.resolve(variables);
    }
  }

  /**
   * 表单
   */
  private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

    private final Encoder encoder;

    private BuildFormEncodedTemplateFromArgs(
        MethodMetadata metadata, Encoder encoder,
        QueryMapEncoder queryMapEncoder, Target<?> target) {
      super(metadata, queryMapEncoder, target);
      this.encoder = encoder;
    }

    @Override
    protected RequestTemplate resolve(
        Object[] argv, RequestTemplate mutable, Map<String, Object> variables) {
      // 处理表单
      Map<String, Object> formVariables = new LinkedHashMap<String, Object>();
      for (Map.Entry<String, Object> entry : variables.entrySet()) {
        if (metadata.formParams().contains(entry.getKey())) {
          formVariables.put(entry.getKey(), entry.getValue());
        }
      }
      // 数据存在RequestTemplate#body中
      try {
        encoder.encode(formVariables, Encoder.MAP_STRING_WILDCARD, mutable);
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      // BuildTemplateFromResolvingArgs 解析模板变量
      return super.resolve(argv, mutable, variables);
    }
  }

  /**
   * 编码响应体
   */
  private static class BuildEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

    private final Encoder encoder;

    private BuildEncodedTemplateFromArgs(
        MethodMetadata metadata, Encoder encoder, QueryMapEncoder queryMapEncoder, Target target) {
      super(metadata, queryMapEncoder, target);
      this.encoder = encoder;
    }

    @Override
    protected RequestTemplate resolve(
        Object[] argv, RequestTemplate mutable, Map<String, Object> variables) {
      boolean alwaysEncodeBody = mutable.methodMetadata().alwaysEncodeBody();
      Object body = null;
      // 获取metadata.bodyIndex参数
      if (!alwaysEncodeBody) {
        body = argv[metadata.bodyIndex()];
        checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
      }
      // 数据存放在body中
      try {
        if (alwaysEncodeBody) {
          body =(argv == null) ? new Object[0] : argv;
          encoder.encode(body, Object[].class, mutable);
        } else {
          encoder.encode(body, metadata.bodyType(), mutable);
        }
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      return super.resolve(argv, mutable, variables);
    }
  }
}

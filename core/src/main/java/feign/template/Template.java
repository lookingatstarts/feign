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
package feign.template;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A Generic representation of a Template Expression as defined by <a
 * href="https://tools.ietf.org/html/rfc6570">RFC 6570</a>, with some relaxed rules, allowing the
 * concept to be used in areas outside of the uri.
 * 1、解析模版表达式，底层依赖于TemplateChunk-> Expression:SimpleExpression PathStyleExpression Literal
 * 2、只有被@Param注释的参数才能作为解析变量模版的值
 *
 * Header uri body都支持模版变量，但是header不需要uri编码
 */
public class Template {

  private static final Logger logger = Logger.getLogger(Template.class.getName());
  private static final Pattern QUERY_STRING_PATTERN = Pattern.compile("(?<!\\{)(\\?)");
  private final String template;
  private final boolean allowUnresolved;
  private final EncodingOptions encode;
  private final boolean encodeSlash;
  private final Charset charset;
  private final List<TemplateChunk> templateChunks = new ArrayList<>();

  /**
   * Create a new Template.
   *
   * @param value of the template.
   * @param allowUnresolved if unresolved expressions should remain.
   * @param encode all values.
   * @param encodeSlash if slash characters should be encoded.
   */
  Template(
      String value,
      ExpansionOptions allowUnresolved,
      EncodingOptions encode,
      boolean encodeSlash,
      Charset charset) {
    if (value == null) {
      throw new IllegalArgumentException("template is required.");
    }
    this.template = value;
    this.allowUnresolved = ExpansionOptions.ALLOW_UNRESOLVED == allowUnresolved;
    this.encode = encode;
    this.encodeSlash = encodeSlash;
    this.charset = charset;
    this.parseTemplate();
  }

  /**
   * 创建字面量
   */
  Template(
      ExpansionOptions allowUnresolved,
      EncodingOptions encode,
      boolean encodeSlash,
      Charset charset,
      List<TemplateChunk> chunks) {
    this.templateChunks.addAll(chunks);
    this.allowUnresolved = ExpansionOptions.ALLOW_UNRESOLVED == allowUnresolved;
    this.encode = encode;
    this.encodeSlash = encodeSlash;
    this.charset = charset;
    this.template = this.toString();
  }

  public String expand(Map<String, ?> variables) {
    if (variables == null) {
      throw new IllegalArgumentException("variable map is required.");
    }
    // 追加所有的templateChunk
    StringBuilder resolved = null;
    for (TemplateChunk chunk : this.templateChunks) {
      String expanded;
      if (chunk instanceof Expression) {
        expanded = this.resolveExpression((Expression) chunk, variables);
      } else {
        expanded = chunk.getValue();
      }
      if (expanded == null) {
        continue;
      }
      if (resolved == null) {
        resolved = new StringBuilder();
      }
      resolved.append(expanded);
    }
    if (resolved == null) {
      return null;
    }
    return resolved.toString();
  }

  protected String resolveExpression(Expression expression, Map<String, ?> variables) {
    String resolved = null;
    Object value = variables.get(expression.getName());
    if (value != null) {
      String expanded = expression.expand(value, this.encode.isEncodingRequired());
      if (expanded != null) {
        if (!this.encodeSlash) {
          logger.fine("Explicit slash decoding specified, decoding all slashes in uri");
          expanded = expanded.replaceAll("%2F", "/");
        }
        resolved = expanded;
      }
    } else {
      if (this.allowUnresolved) {
        /* unresolved variables are treated as literals */
        resolved = encodeLiteral(expression.toString());
      }
    }
    return resolved;
  }

  /**
   * Uri Encode the value.
   *
   * @param value to encode.
   * @return the encoded value.
   */
  private String encodeLiteral(String value) {
    return this.encodeLiteral() ? UriUtils.encode(value, this.charset, true) : value;
  }

  /**
   * {name:pattern} -> name就是变量，存储在Expression.name字段
   */
  public List<String> getVariables() {
    // Expression.getName()
    return this.templateChunks.stream()
        .filter(templateChunk -> Expression.class.isAssignableFrom(templateChunk.getClass()))
        .map(templateChunk -> ((Expression) templateChunk).getName())
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  public List<String> getLiterals() {
    return this.templateChunks.stream()
        .filter(templateChunk -> Literal.class.isAssignableFrom(templateChunk.getClass()))
        .map(TemplateChunk::toString)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  public List<TemplateChunk> getTemplateChunks() {
    return Collections.unmodifiableList(this.templateChunks);
  }

  public boolean isLiteral() {
    return this.getVariables().isEmpty();
  }

  private void parseTemplate() {
    this.parseFragment(this.template);
  }

  private void parseFragment(String fragment) {
    ChunkTokenizer tokenizer = new ChunkTokenizer(fragment);
    while (tokenizer.hasNext()) {
      String chunk = tokenizer.next();
      if (chunk.startsWith("{")) {
        Expression expression = Expressions.create(chunk);
        if (expression == null) {
          this.templateChunks.add(Literal.create(this.encodeLiteral(chunk)));
        } else {
          this.templateChunks.add(expression);
        }
      } else {
        this.templateChunks.add(Literal.create(this.encodeLiteral(chunk)));
      }
    }
  }

  @Override
  public String toString() {
    // 如果Expression，getValue()的逻辑返回模版将 {name:pattern} 拼接
    // 如果是Literal,getValue()直接返回value
    return this.templateChunks.stream().map(TemplateChunk::getValue).collect(Collectors.joining());
  }

  public boolean encodeLiteral() {
    return encode.isEncodingRequired();
  }

  boolean encodeSlash() {
    return encodeSlash;
  }

  public Charset getCharset() {
    return this.charset;
  }

  static class ChunkTokenizer {

    private List<String> tokens = new ArrayList<>();
    private int index;

    ChunkTokenizer(String template) {
      boolean outside = true;
      int level = 0;
      int lastIndex = 0;
      int idx;

      /* loop through the template, character by character */
      for (idx = 0; idx < template.length(); idx++) {
        if (template.charAt(idx) == '{') {
          /* start of an expression */
          if (outside) {
            /* outside of an expression */
            if (lastIndex < idx) {
              /* this is the start of a new token */
              tokens.add(template.substring(lastIndex, idx));
            }
            lastIndex = idx;

            /*
             * no longer outside of an expression, additional characters will be treated as in an
             * expression
             */
            outside = false;
          } else {
            /* nested braces, increase our nesting level */
            level++;
          }
        } else if (template.charAt(idx) == '}' && !outside) {
          /* the end of an expression */
          if (level > 0) {
            /*
             * sometimes we see nested expressions, we only want the outer most expression
             * boundaries.
             */
            level--;
          } else {
            /* outermost boundary */
            if (lastIndex < idx) {
              /* this is the end of an expression token */
              tokens.add(template.substring(lastIndex, idx + 1));
            }
            lastIndex = idx + 1;

            /* outside an expression */
            outside = true;
          }
        }
      }
      if (lastIndex < idx) {
        /* grab the remaining chunk */
        tokens.add(template.substring(lastIndex, idx));
      }
    }

    public boolean hasNext() {
      return this.tokens.size() > this.index;
    }

    public String next() {
      if (hasNext()) {
        return this.tokens.get(this.index++);
      }
      throw new IllegalStateException("No More Elements");
    }
  }

  public enum EncodingOptions {
    REQUIRED(true),
    NOT_REQUIRED(false);

    private final boolean shouldEncode;

    EncodingOptions(boolean shouldEncode) {
      this.shouldEncode = shouldEncode;
    }

    public boolean isEncodingRequired() {
      return this.shouldEncode;
    }
  }

  public enum ExpansionOptions {
    ALLOW_UNRESOLVED,
    REQUIRED
  }
}

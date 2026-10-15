package com.zhice;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OllamaGateway implements ModelGateway {
  public OllamaGateway(String url, String chat, String embed) {
    this(url, chat, embed, false);
  }

  private final URI endpoint;
  private final String chatModel, embedModel;
  private final HttpClient client;
  private final boolean thoughtInContent;
  private static final Pattern THOUGHT_MARKER =
      Pattern.compile(
          "(?i)<\\s*/?\\s*(?:think|thin(?:\\s*>|$)|thi(?:\\s*>|$)|th(?:\\s*>|$)|t(?:\\s*>|$))");
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  @Autowired
  public OllamaGateway(
      @Value("${zhice.ollama-url:http://127.0.0.1:11435}") String url,
      @Value("${zhice.chat-model:}") String chat,
      @Value("${zhice.embed-model:}") String embed,
      @Value("${zhice.thought-in-content:${ZHICE_THOUGHT_IN_CONTENT:false}}")
          boolean thoughtInContent) {
    URI parsed;
    try {
      parsed = URI.create(url);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Model URL must be an explicit HTTP loopback endpoint");
    }
    if (!"http".equals(parsed.getScheme())
        || parsed.getHost() == null
        || !Set.of("127.0.0.1", "[::1]", "::1").contains(parsed.getHost())
        || parsed.getUserInfo() != null
        || parsed.getRawQuery() != null
        || parsed.getFragment() != null
        || !(parsed.getPath().isEmpty() || parsed.getPath().equals("/"))
        || parsed.getPort() == 0)
      throw new IllegalArgumentException(
          "Model URL must be an explicit HTTP loopback endpoint without paths or credentials");
    endpoint = parsed;
    chatModel = checkedModel(chat);
    embedModel = checkedModel(embed);
    this.thoughtInContent = thoughtInContent;
    client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .proxy(
                new ProxySelector() {
                  public List<Proxy> select(URI uri) {
                    return List.of(Proxy.NO_PROXY);
                  }

                  public void connectFailed(URI uri, SocketAddress address, IOException ex) {}
                })
            .build();
  }

  private String checkedModel(String model) {
    String name = model == null ? "" : model.strip();
    if (name.length() > 150 || name.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid model name");
    return name;
  }

  public boolean chatConfigured() {
    return !chatModel.isBlank();
  }

  public boolean embeddingConfigured() {
    return !embedModel.isBlank();
  }

  public List<List<Double>> embed(List<String> texts, Duration timeout) {
    if (!embeddingConfigured())
      throw new KnowledgeException(422, "EMBEDDING_NOT_CONFIGURED", "尚未配置本地嵌入模型");
    if (texts == null
        || texts.isEmpty()
        || texts.size() > 16
        || texts.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 12000))
      throw new KnowledgeException(422, "INVALID_EMBED_INPUT", "嵌入输入超出范围");
    var response =
        post(
            "/api/embed",
            Map.of("model", embedModel, "input", texts, "truncate", false, "keep_alive", "10m"),
            timeout);
    var arrays = response.path("embeddings");
    if (!arrays.isArray() || arrays.size() != texts.size()) throw malformed();
    var out = new ArrayList<List<Double>>();
    int dimensions = -1;
    for (var row : arrays) {
      if (!row.isArray()) throw malformed();
      var vector = new ArrayList<Double>();
      for (var number : row) {
        if (!number.isNumber()) throw malformed();
        vector.add(number.doubleValue());
      }
      Retrieval.validateVector(vector);
      if (dimensions < 0) dimensions = vector.size();
      if (dimensions != vector.size()) throw malformed();
      out.add(List.copyOf(vector));
    }
    return List.copyOf(out);
  }

  public Message chat(List<Message> messages, List<Map<String, Object>> tools, Duration timeout) {
    if (!chatConfigured()) throw new KnowledgeException(422, "CHAT_NOT_CONFIGURED", "尚未配置本地对话模型");
    var body = new LinkedHashMap<String, Object>();
    body.put("model", chatModel);
    body.put("stream", false);
    body.put("think", false);
    body.put("keep_alive", "10m");
    body.put("options", Map.of("temperature", 0, "num_predict", 1000, "num_ctx", 8192));
    var history = new ArrayList<Map<String, Object>>();
    for (var message : messages) {
      var item = new LinkedHashMap<String, Object>();
      item.put("role", message.role());
      item.put("content", message.content() == null ? "" : message.content());
      if ("tool".equals(message.role()) && message.toolName() != null)
        item.put("tool_name", message.toolName());
      if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
        var calls = new ArrayList<Map<String, Object>>();
        for (var call : message.toolCalls())
          calls.add(Map.of("function", Map.of("name", call.name(), "arguments", call.arguments())));
        item.put("tool_calls", calls);
      }
      history.add(item);
    }
    body.put("messages", history);
    if (!tools.isEmpty()) body.put("tools", tools);
    var response = post("/api/chat", body, timeout);
    var output = response.path("message");
    if (!response.path("done").asBoolean()
        || !output.isObject()
        || !"assistant".equals(output.path("role").asText())
        || !output.path("content").isString()) throw malformed();
    String content = output.path("content").asText();
    if (content.length() > 12000) throw malformed();
    var toolCalls = new ArrayList<ToolCall>();
    var calls = output.path("tool_calls");
    if (!calls.isMissingNode() && !calls.isNull()) {
      if (!calls.isArray() || calls.size() > 4) throw malformed();
      for (var call : calls) {
        var function = call.path("function");
        var args = function.path("arguments");
        if (!function.path("name").isString()
            || !args.isObject()
            || function.path("name").asText().length() > 80) throw malformed();
        var map = new LinkedHashMap<String, Object>();
        for (var entry : args.properties()) {
          if (!entry.getValue().isString()) throw malformed();
          map.put(entry.getKey(), entry.getValue().asText());
        }
        toolCalls.add(
            new ToolCall(
                "call-" + toolCalls.size(), function.path("name").asText(), Map.copyOf(map)));
      }
    }
    content = finalContent(content, !toolCalls.isEmpty());
    if (content.isBlank() && toolCalls.isEmpty()) throw malformed();
    return new Message("assistant", content, List.copyOf(toolCalls));
  }

  /** Only an explicitly verified legacy template may place a thought prefix in content. */
  private String finalContent(String content, boolean hasTools) {
    if (!thoughtInContent) {
      if (THOUGHT_MARKER.matcher(content).find()) throw thoughtBoundary();
      return content;
    }
    // Tool calls are structured separately; none of this legacy text is needed or retained.
    if (hasTools) return "";
    int close = content.indexOf("</think>");
    if (close < 0 || content.indexOf("</think>", close + 8) >= 0) throw thoughtBoundary();
    String prefix = content.substring(0, close).stripLeading();
    if (prefix.startsWith("<think>")) prefix = prefix.substring(7);
    if (THOUGHT_MARKER.matcher(prefix).find()) throw thoughtBoundary();
    String answer = content.substring(close + 8).strip();
    if (answer.isEmpty() || THOUGHT_MARKER.matcher(answer).find()) throw thoughtBoundary();
    return answer;
  }

  private static KnowledgeException thoughtBoundary() {
    return new KnowledgeException(502, "MODEL_THOUGHT_BOUNDARY", "模型响应未提供可安全区分的最终答案，请检查模型模板配置");
  }

  private JsonNode post(String path, Map<String, Object> payload, Duration timeout) {
    CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new TimeoutException();
      long millis = Math.max(1, Math.min(90000, timeout.toMillis()));
      var request =
          HttpRequest.newBuilder(endpoint.resolve(path))
              .timeout(Duration.ofMillis(millis))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
              .build();
      pending = client.sendAsync(request, info -> new LimitedBody(1024 * 1024));
      var response = pending.get(millis, TimeUnit.MILLISECONDS);
      if (response.statusCode() != 200)
        throw new KnowledgeException(502, "MODEL_HTTP", "本地模型服务未成功处理请求，请检查模型配置");
      return JSON.readTree(response.body());
    } catch (KnowledgeException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      if (pending != null) pending.cancel(true);
      throw new KnowledgeException(502, "MODEL_INTERRUPTED", "模型请求已中断");
    } catch (TimeoutException ex) {
      if (pending != null) pending.cancel(true);
      throw new KnowledgeException(502, "MODEL_TIMEOUT", "本地模型响应超时，请稍后重试");
    } catch (Exception ex) {
      if (pending != null) pending.cancel(true);
      throw new KnowledgeException(502, "MODEL_RESPONSE", "本地模型连接失败或响应格式无效");
    }
  }

  private static KnowledgeException malformed() {
    return new KnowledgeException(502, "MODEL_RESPONSE", "本地模型响应结构无效");
  }

  /**
   * Caps response bytes while the outer future enforces a deadline through the full response body.
   */
  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int max;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedBody(int max) {
      this.max = max;
    }

    public CompletionStage<byte[]> getBody() {
      return result;
    }

    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      s.request(1);
    }

    public void onNext(List<ByteBuffer> buffers) {
      try {
        for (var buffer : buffers) {
          if (buffer.remaining() > max - bytes.size()) {
            subscription.cancel();
            result.completeExceptionally(new IOException("Response limit"));
            return;
          }
          byte[] part = new byte[buffer.remaining()];
          buffer.get(part);
          bytes.write(part);
        }
        subscription.request(1);
      } catch (Exception ex) {
        subscription.cancel();
        result.completeExceptionally(ex);
      }
    }

    public void onError(Throwable ex) {
      result.completeExceptionally(ex);
    }

    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}

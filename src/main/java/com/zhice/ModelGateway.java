package com.zhice;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Boundary to the configured local model runtime; no persistence or tools execute here. */
public interface ModelGateway {
  record ToolCall(String id, String name, Map<String, Object> arguments) {}

  record Message(String role, String content, List<ToolCall> toolCalls, String toolName) {
    public Message(String role, String content, List<ToolCall> toolCalls) {
      this(role, content, toolCalls, null);
    }

    public Message(String role, String content) {
      this(role, content, List.of(), null);
    }
  }

  boolean chatConfigured();

  boolean embeddingConfigured();

  List<List<Double>> embed(List<String> texts, Duration timeout);

  Message chat(List<Message> messages, List<Map<String, Object>> tools, Duration timeout);
}

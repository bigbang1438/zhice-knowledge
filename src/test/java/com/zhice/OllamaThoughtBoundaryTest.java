package com.zhice;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OllamaThoughtBoundaryTest {
  // These are synthetic sentinels, never actual model analysis.
  @Test
  void defaultRejectsThoughtMarkersBeforeTheyCanReachTheCaller() throws Exception {
    for (String content :
        List.of(
            "private-sentinel</think>Final.[1]",
            "<think>unfinished",
            "private-sentinel</thin",
            "<THINK>unexpected")) {
      reply(
          content,
          false,
          false,
          gateway -> {
            var ex = assertThrows(KnowledgeException.class, () -> chat(gateway));
            assertEquals(502, ex.status());
            assertFalse(ex.getMessage().contains("private-sentinel"));
          });
    }
  }

  @Test
  void explicitVerifiedLegacyModeReturnsOnlyFinalContent() throws Exception {
    for (String content :
        List.of(
            "private-sentinel</think>\n退款期限七天。[1]",
            "<think>private-sentinel</think>\n退款期限七天。[1]")) {
      reply(content, false, true, gateway -> assertEquals("退款期限七天。[1]", chat(gateway).content()));
    }
  }

  @Test
  void explicitLegacyModeRejectsMissingPartialRepeatedOrMisorderedBoundaries() throws Exception {
    for (String content :
        List.of(
            "private-sentinel only",
            "<think>unfinished",
            "private-sentinel</thin",
            "private-sentinel</think>Final.[1]</think>",
            "private-sentinel</think>Final.[1]<think>again",
            "private-sentinel</think>   ",
            "private-sentinel</think>Final.[1]</thin",
            "<think><think>private-sentinel</think>Final.[1]")) {
      reply(
          content,
          false,
          true,
          gateway -> assertThrows(KnowledgeException.class, () -> chat(gateway)));
    }
  }

  @Test
  void explicitLegacyModeDiscardsAllContentWhenValidatedToolCallsExist() throws Exception {
    reply(
        "private-sentinel including an unfinished <think>",
        true,
        true,
        gateway -> {
          var result = chat(gateway);
          assertEquals("", result.content());
          assertEquals("search_knowledge", result.toolCalls().getFirst().name());
          assertEquals("退款", result.toolCalls().getFirst().arguments().get("query"));
        });
  }

  private static ModelGateway.Message chat(OllamaGateway gateway) {
    return gateway.chat(
        List.of(new ModelGateway.Message("user", "退款?")), List.of(), Duration.ofSeconds(2));
  }

  private static void reply(
      String content, boolean tools, boolean legacy, Consumer<OllamaGateway> assertion)
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var message = new java.util.LinkedHashMap<String, Object>();
    message.put("role", "assistant");
    message.put("content", content);
    if (tools)
      message.put(
          "tool_calls",
          List.of(
              Map.of(
                  "function",
                  Map.of("name", "search_knowledge", "arguments", Map.of("query", "退款")))));
    byte[] body = new JsonMapper().writeValueAsBytes(Map.of("done", true, "message", message));
    server.createContext(
        "/api/chat",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      assertion.accept(
          new OllamaGateway(
              "http://127.0.0.1:" + server.getAddress().getPort(), "chat", "", legacy));
    } finally {
      server.stop(0);
    }
  }
}

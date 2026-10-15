package com.zhice;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OllamaGatewayTest {
  @Test
  void nullToolArgumentIsAControlledModelError() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/chat",
        exchange -> {
          byte[] body =
              "{\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"search_knowledge\",\"arguments\":{\"query\":null}}}]}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var gateway =
          new OllamaGateway("http://127.0.0.1:" + server.getAddress().getPort(), "chat", "");
      var ex =
          assertThrows(
              KnowledgeException.class,
              () ->
                  gateway.chat(
                      List.of(new ModelGateway.Message("user", "hi")),
                      List.of(),
                      Duration.ofSeconds(2)));
      assertEquals(502, ex.status());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void sendsToolObservationNameToOllama() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var json = new JsonMapper();
    server.createContext(
        "/api/chat",
        exchange -> {
          var body = json.readTree(exchange.getRequestBody());
          if (!"search_knowledge".equals(body.path("messages").get(0).path("tool_name").asText())) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
          }
          byte[] response =
              "{\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"七天。[1]\"}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      var gateway =
          new OllamaGateway("http://127.0.0.1:" + server.getAddress().getPort(), "chat", "");
      var result =
          gateway.chat(
              List.of(new ModelGateway.Message("tool", "[1] 七天", List.of(), "search_knowledge")),
              List.of(),
              Duration.ofSeconds(2));
      assertEquals("七天。[1]", result.content());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsRemoteEndpointAndUrlsWithCredentialsPathsAndQueries() {
    for (String url :
        List.of(
            "https://127.0.0.1:11435",
            "http://example.com",
            "http://127.0.0.1.evil.test",
            "http://me:pass@127.0.0.1",
            "http://127.0.0.1/secret",
            "http://127.0.0.1/?x=a"))
      assertThrows(
          IllegalArgumentException.class, () -> new OllamaGateway(url, "chat", "embed"), url);
  }

  @Test
  void parsesRealHttpMessageAndNativeToolCallsWithoutExecutingThem() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var json = new JsonMapper();
    server.createContext(
        "/api/chat",
        exchange -> {
          var request = json.readTree(exchange.getRequestBody());
          if (!request.path("model").asText().equals("chat")
              || request.path("stream").asBoolean()
              || !request.path("tools").isArray()) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
          }
          byte[] body =
              "{\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"search_knowledge\",\"arguments\":{\"query\":\"退款\"}}}]}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var gateway =
          new OllamaGateway("http://127.0.0.1:" + server.getAddress().getPort(), "chat", "embed");
      var output =
          gateway.chat(
              List.of(new ModelGateway.Message("user", "退款?")),
              List.of(Map.of("type", "function")),
              Duration.ofSeconds(2));
      assertEquals("search_knowledge", output.toolCalls().getFirst().name());
      assertEquals("退款", output.toolCalls().getFirst().arguments().get("query"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void parsesEmbeddingsAndRejectsWrongShape() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/embed",
        exchange -> {
          byte[] body = "{\"embeddings\":[[1.0,0.0],[0.0,1.0]]}".getBytes();
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var gateway =
          new OllamaGateway("http://127.0.0.1:" + server.getAddress().getPort(), "chat", "embed");
      assertEquals(
          List.of(List.of(1d, 0d), List.of(0d, 1d)),
          gateway.embed(List.of("a", "b"), Duration.ofSeconds(2)));
      assertThrows(
          KnowledgeException.class, () -> gateway.embed(List.of("a"), Duration.ofSeconds(2)));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void doesNotFollowRedirectsOrReturnRawModelFailure() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/chat",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "http://example.com/private");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.start();
    try {
      var gateway =
          new OllamaGateway("http://127.0.0.1:" + server.getAddress().getPort(), "chat", "");
      var ex =
          assertThrows(
              KnowledgeException.class,
              () ->
                  gateway.chat(
                      List.of(new ModelGateway.Message("user", "hi")),
                      List.of(),
                      Duration.ofSeconds(2)));
      assertFalse(ex.getMessage().contains("example.com"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void configurationCannotSilentlyProduceAnAnswerOrVector() {
    var gateway = new OllamaGateway("http://127.0.0.1:11435", "", "");
    assertFalse(gateway.chatConfigured());
    assertFalse(gateway.embeddingConfigured());
    assertThrows(
        KnowledgeException.class,
        () ->
            gateway.chat(
                List.of(new ModelGateway.Message("user", "hi")), List.of(), Duration.ofSeconds(1)));
    assertThrows(
        KnowledgeException.class, () -> gateway.embed(List.of("hi"), Duration.ofSeconds(1)));
  }
}

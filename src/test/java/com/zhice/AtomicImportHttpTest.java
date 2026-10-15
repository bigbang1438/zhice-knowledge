package com.zhice;

import static com.zhice.KnowledgeHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.datasource.url=jdbc:h2:mem:zhice-atomic-import;DB_CLOSE_DELAY=-1"})
@Import(AtomicImportHttpTest.GatewayConfiguration.class)
class AtomicImportHttpTest {
  @LocalServerPort int port;
  @Autowired EmbeddingGateway gateway;

  @DynamicPropertySource
  static void password(DynamicPropertyRegistry registry) {
    registry.add("zhice.demo-password", () -> PASSWORD);
  }

  @Test
  void slowEmbeddingDoesNotLockKnowledgeAndStaleReplacementCannotOverwriteWinner()
      throws Exception {
    var b = new Browser(port);
    b.login("demo_anna");
    var created =
        b.upload("POST", "team-guide", null, null, "approval.md", "Original approval rule");
    assertEquals(201, created.statusCode(), created.body());
    String did = tree(created).path("id").asText();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<HttpResponse<String>> slow =
          executor.submit(
              () -> b.upload("PUT", "team-guide", did, 1, "approval.md", "SLOW replacement rule"));
      assertTrue(gateway.entered.await(10, TimeUnit.SECONDS));
      var other = new Browser(port);
      other.login("demo_anna");
      var winner = other.upload("PUT", "team-guide", did, 1, "approval.md", "Winner approval rule");
      assertEquals(200, winner.statusCode(), winner.body());
      gateway.release.countDown();
      assertEquals(409, slow.get(10, TimeUnit.SECONDS).statusCode());
      var sources =
          tree(other.send("GET", "/api/spaces/team-guide/search?q=approval&mode=bm25", null))
              .path("sources");
      assertTrue(sources.toString().contains("Winner approval"));
      assertFalse(sources.toString().contains("SLOW"));
      assertEquals(
          2,
          tree(other.send("GET", "/api/spaces/team-guide/documents", null))
              .path("items")
              .get(0)
              .path("version")
              .asInt());
    } finally {
      gateway.release.countDown();
      executor.shutdownNow();
    }
  }

  @TestConfiguration
  static class GatewayConfiguration {
    @Bean
    @Primary
    EmbeddingGateway embeddingGateway() {
      return new EmbeddingGateway();
    }
  }

  static final class EmbeddingGateway implements ModelGateway {
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

    public boolean chatConfigured() {
      return false;
    }

    public boolean embeddingConfigured() {
      return true;
    }

    public Message chat(List<Message> messages, List<Map<String, Object>> tools, Duration timeout) {
      throw new AssertionError("No chat in import test");
    }

    public List<List<Double>> embed(List<String> texts, Duration timeout) {
      if (texts.stream().anyMatch(t -> t.contains("SLOW"))) {
        entered.countDown();
        try {
          if (!release.await(15, TimeUnit.SECONDS))
            throw new IllegalStateException("Fixture timeout");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      return texts.stream().map(t -> List.of(1.0, 0.5)).toList();
    }
  }
}

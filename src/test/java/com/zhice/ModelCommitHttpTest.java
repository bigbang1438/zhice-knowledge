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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.datasource.url=jdbc:h2:mem:zhice-model-commit;DB_CLOSE_DELAY=-1"})
@Import(ModelCommitHttpTest.GatewayConfiguration.class)
class ModelCommitHttpTest {
  @DynamicPropertySource
  static void password(DynamicPropertyRegistry registry) {
    registry.add("zhice.demo-password", () -> PASSWORD);
  }

  @LocalServerPort int port;
  @Autowired ControlledGateway gateway;

  @Test
  void changedKnowledgeRejectsAnswerAndDoesNotSaveAStaleTurn() throws Exception {
    var b = new Browser(port);
    b.login("demo_anna");
    var document =
        b.upload(
            "POST",
            "team-guide",
            null,
            null,
            "remote.md",
            "# Remote work\nRemote work requires manager approval.");
    assertEquals(201, document.statusCode(), document.body());
    String did = tree(document).path("id").asText();
    var conversation =
        b.send("POST", "/api/spaces/team-guide/conversations", "{\"title\":\"Stale answer\"}");
    String cid = tree(conversation).path("id").asText();
    gateway.blockNext();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<HttpResponse<String>> answering =
          executor.submit(
              () ->
                  b.send(
                      "POST",
                      "/api/spaces/team-guide/conversations/" + cid + "/messages",
                      "{\"question\":\"Remote work"
                          + " approval?\",\"mode\":\"rag\",\"searchMode\":\"bm25\"}"));
      assertTrue(gateway.entered.await(10, TimeUnit.SECONDS));
      var editor = new Browser(port);
      editor.login("demo_anna");
      var updated =
          editor.upload(
              "PUT",
              "team-guide",
              did,
              1,
              "remote.md",
              "# Remote work\nRemote work requires director approval.");
      assertEquals(200, updated.statusCode(), updated.body());
      gateway.release.countDown();
      var rejected = answering.get(10, TimeUnit.SECONDS);
      assertEquals(409, rejected.statusCode(), rejected.body());
      assertEquals(
          0,
          tree(b.send("GET", "/api/spaces/team-guide/conversations/" + cid, null))
              .path("turns")
              .size());
    } finally {
      gateway.release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void overlappingAnswerRunIsRejectedAndSuccessfulTurnIsPersisted() throws Exception {
    var b = new Browser(port);
    b.login("demo_anna");
    assertEquals(
        201,
        b.upload(
                "POST",
                "team-guide",
                null,
                null,
                "vacation.md",
                "# Vacation\nVacation approval is required.")
            .statusCode());
    String cid =
        tree(b.send("POST", "/api/spaces/team-guide/conversations", "{\"title\":\"Vacation\"}"))
            .path("id")
            .asText();
    gateway.blockNext();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<HttpResponse<String>> first =
          executor.submit(
              () ->
                  b.send(
                      "POST",
                      "/api/spaces/team-guide/conversations/" + cid + "/messages",
                      "{\"question\":\"Vacation"
                          + " approval?\",\"mode\":\"rag\",\"searchMode\":\"bm25\"}"));
      assertTrue(gateway.entered.await(10, TimeUnit.SECONDS));
      var secondSession = new Browser(port);
      secondSession.login("demo_anna");
      assertEquals(
          429,
          secondSession
              .send(
                  "POST",
                  "/api/spaces/team-guide/conversations/" + cid + "/messages",
                  "{\"question\":\"Vacation?\",\"mode\":\"rag\",\"searchMode\":\"bm25\"}")
              .statusCode());
      gateway.release.countDown();
      assertEquals(201, first.get(10, TimeUnit.SECONDS).statusCode());
      var saved =
          tree(b.send("GET", "/api/spaces/team-guide/conversations/" + cid, null)).path("turns");
      assertEquals(1, saved.size());
      assertEquals("Vacation approval?", saved.get(0).path("question").asText());
      assertFalse(saved.get(0).path("sources").isEmpty());
    } finally {
      gateway.release.countDown();
      executor.shutdownNow();
    }
  }

  @TestConfiguration
  static class GatewayConfiguration {
    @Bean
    @Primary
    ControlledGateway controlledGateway() {
      return new ControlledGateway();
    }
  }

  static final class ControlledGateway implements ModelGateway {
    volatile CountDownLatch entered = new CountDownLatch(0), release = new CountDownLatch(0);

    void blockNext() {
      entered = new CountDownLatch(1);
      release = new CountDownLatch(1);
    }

    public boolean chatConfigured() {
      return true;
    }

    public boolean embeddingConfigured() {
      return false;
    }

    public List<List<Double>> embed(List<String> texts, Duration timeout) {
      throw new AssertionError("No embedding requested in BM25 fixture");
    }

    public Message chat(List<Message> messages, List<Map<String, Object>> tools, Duration timeout) {
      entered.countDown();
      try {
        if (!release.await(15, TimeUnit.SECONDS))
          throw new IllegalStateException("Fixture timed out");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      return new Message("assistant", "Approval is required. [1]", List.of());
    }
  }
}

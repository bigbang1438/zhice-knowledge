package com.zhice;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:zhice-http;DB_CLOSE_DELAY=-1",
      "zhice.chat-model=",
      "zhice.embed-model="
    })
class KnowledgeHttpTest {
  static final String PASSWORD = UUID.randomUUID().toString();
  static final JsonMapper JSON = JsonMapper.builder().build();

  @DynamicPropertySource
  static void password(DynamicPropertyRegistry registry) {
    registry.add("zhice.demo-password", () -> PASSWORD);
  }

  @LocalServerPort int port;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Test
  void publicSessionProvidesCsrfAndProtectedApiRejectsAnonymous() throws Exception {
    Browser b = new Browser(port);
    var session = b.session();
    assertTrue(session.path("user").isNull());
    assertFalse(session.path("models").path("chatConfigured").asBoolean());
    var denied = b.send("GET", "/api/spaces", null);
    assertEquals(401, denied.statusCode());
    assertEquals("unauthorized", tree(denied).path("error").path("code").asText());
  }

  @Test
  void csrfAndForeignOriginCannotMutateOrLogin() throws Exception {
    Browser b = new Browser(port);
    b.session();
    assertEquals(
        403,
        b.send(
                "POST",
                "/api/login",
                "username=demo_anna&password=" + PASSWORD,
                false,
                "application/x-www-form-urlencoded",
                null)
            .statusCode());
    assertEquals(
        403,
        b.send(
                "POST",
                "/api/login",
                "username=demo_anna&password=" + PASSWORD,
                true,
                "application/x-www-form-urlencoded",
                "https://foreign.example")
            .statusCode());
    b.login("demo_anna");
    assertEquals(
        403,
        b.send(
                "POST",
                "/api/spaces/team-guide/conversations",
                "{\"title\":\"Blocked\"}",
                false,
                "application/json",
                null)
            .statusCode());
  }

  @Test
  void usersOnlySeeAndOperateTheirOwnSpace() throws Exception {
    Browser anna = new Browser(port);
    anna.login("demo_anna");
    var spaces = tree(anna.send("GET", "/api/spaces", null)).path("items");
    assertEquals(1, spaces.size());
    assertEquals("team-guide", spaces.get(0).path("id").asText());
    assertEquals(403, anna.send("GET", "/api/spaces/private-guide/documents", null).statusCode());
    assertEquals(
        403, anna.upload("POST", "private-guide", null, null, "secret.md", "no").statusCode());
    Browser ben = new Browser(port);
    ben.login("demo_ben");
    assertEquals(
        403,
        ben.send("GET", "/api/spaces/team-guide/search?q=secret&mode=bm25", null).statusCode());
  }

  @Test
  void documentReplacementIsVersionedAndFailedParsePreservesPriorIndex() throws Exception {
    Browser b = new Browser(port);
    b.login("demo_anna");
    var created =
        b.upload(
            "POST",
            "team-guide",
            null,
            null,
            "guide.md",
            "# Travel\nTrain tickets require approval.");
    assertEquals(201, created.statusCode(), created.body());
    String id = tree(created).path("id").asText();
    assertEquals(1, tree(created).path("version").asInt());
    var failed = b.upload("PUT", "team-guide", id, 1, "scan.pdf", "not a pdf");
    assertEquals(422, failed.statusCode(), failed.body());
    var found =
        tree(b.send("GET", "/api/spaces/team-guide/search?q=Train&mode=bm25&limit=5", null))
            .path("sources");
    assertTrue(found.toString().contains("Train tickets"));
    var replaced =
        b.upload(
            "PUT", "team-guide", id, 1, "guide.md", "# Travel\nFlights need manager approval.");
    assertEquals(200, replaced.statusCode(), replaced.body());
    assertEquals(2, tree(replaced).path("version").asInt());
    assertEquals(
        409, b.upload("PUT", "team-guide", id, 1, "guide.md", "stale content").statusCode());
    assertEquals(
        409,
        b.send("DELETE", "/api/spaces/team-guide/documents/" + id + "?version=1", null)
            .statusCode());
    assertEquals(
        204,
        b.send("DELETE", "/api/spaces/team-guide/documents/" + id + "?version=2", null)
            .statusCode());
    var deleted =
        tree(b.send("GET", "/api/spaces/team-guide/search?q=Flights&mode=bm25", null))
            .path("sources");
    assertFalse(deleted.toString().contains(id));
  }

  @Test
  void malformedUploadsAndSearchInputsAreRejectedSafely() throws Exception {
    Browser b = new Browser(port);
    b.login("demo_anna");
    assertEquals(
        422, b.upload("POST", "team-guide", null, null, "../outside.md", "text").statusCode());
    assertEquals(
        422, b.upload("POST", "team-guide", null, null, "program.exe", "text").statusCode());
    assertEquals(422, b.upload("POST", "team-guide", null, null, "empty.md", "   ").statusCode());
    assertEquals(
        422, b.send("GET", "/api/spaces/team-guide/search?q=x&mode=wrong", null).statusCode());
    assertEquals(
        422, b.send("GET", "/api/spaces/team-guide/search?q=x&limit=11", null).statusCode());
    assertEquals(
        503, b.send("GET", "/api/spaces/team-guide/search?q=x&mode=vector", null).statusCode());
    var bad = b.send("POST", "/api/spaces/team-guide/conversations", "{broken");
    assertEquals(422, bad.statusCode());
    assertTrue(tree(bad).has("error"));
    assertFalse(bad.body().contains("Exception"));
  }

  @Test
  void conversationIdsCannotCrossAccountsAndHistoryIsScoped() throws Exception {
    Browser b = new Browser(port);
    b.login("demo_anna");
    var create =
        b.send("POST", "/api/spaces/team-guide/conversations", "{\"title\":\"New topic\"}");
    assertEquals(201, create.statusCode(), create.body());
    String cid = tree(create).path("id").asText();
    var empty = tree(b.send("GET", "/api/spaces/team-guide/conversations/" + cid, null));
    assertEquals("New topic", empty.path("title").asText());
    assertEquals(0, empty.path("turns").size());
    Browser ben = new Browser(port);
    ben.login("demo_ben");
    assertEquals(
        404, ben.send("GET", "/api/spaces/private-guide/conversations/" + cid, null).statusCode());
    assertEquals(
        403, ben.send("GET", "/api/spaces/team-guide/conversations/" + cid, null).statusCode());
    assertEquals(
        503,
        b.send(
                "POST",
                "/api/spaces/team-guide/conversations/" + cid + "/messages",
                "{\"question\":\"What is the policy?\",\"mode\":\"rag\",\"searchMode\":\"bm25\"}")
            .statusCode());
    assertEquals(
        0,
        tree(b.send("GET", "/api/spaces/team-guide/conversations/" + cid, null))
            .path("turns")
            .size());
  }

  @Test
  void oversizedFileAndUnknownLengthJsonAreBounded() throws Exception {
    Browser b = new Browser(port);
    b.login("demo_anna");
    assertEquals(
        413,
        b.upload("POST", "team-guide", null, null, "large.md", "a".repeat(2 * 1024 * 1024 + 1))
            .statusCode());
    byte[] oversized =
        ("{\"title\":\"" + "a".repeat(70000) + "\"}").getBytes(StandardCharsets.UTF_8);
    var request =
        HttpRequest.newBuilder(URI.create(b.base + "/api/spaces/team-guide/conversations"))
            .header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", b.csrf)
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(oversized)))
            .build();
    assertEquals(413, b.client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void loginFailureAndLogoutKeepPublicSessionAccurate() throws Exception {
    Browser b = new Browser(port);
    b.session();
    assertEquals(
        401,
        b.send(
                "POST",
                "/api/login",
                "username=demo_anna&password=wrong",
                true,
                "application/x-www-form-urlencoded",
                null)
            .statusCode());
    b.login("demo_anna");
    assertEquals("anna", b.session().path("user").path("id").asText());
    assertEquals(200, b.send("POST", "/api/logout", null).statusCode());
    assertTrue(b.session().path("user").isNull());
    assertEquals(401, b.send("GET", "/api/spaces", null).statusCode());
  }

  @Test
  void missingRoutesProduceSafeNotFoundErrors() throws Exception {
    Browser b = new Browser(port);
    b.login("demo_anna");
    var missing = b.send("GET", "/api/spaces/team-guide/nonexistent", null);
    assertEquals(404, missing.statusCode(), missing.body());
    assertTrue(tree(missing).has("error"));
  }

  @Test
  void missingFilePartProducesSafeValidationError() throws Exception {
    Browser b = new Browser(port);
    b.login("demo_anna");
    var noFile =
        b.send(
            "POST",
            "/api/spaces/team-guide/documents",
            "--empty--\r\n",
            true,
            "multipart/form-data; boundary=empty",
            null);
    assertEquals(422, noFile.statusCode(), noFile.body());
    assertTrue(tree(noFile).has("error"));
  }

  @Test
  void conversationOwnershipStillAppliesWhenUsersShareAWorkspace() throws Exception {
    Browser anna = new Browser(port);
    anna.login("demo_anna");
    String cid =
        tree(anna.send(
                "POST",
                "/api/spaces/team-guide/conversations",
                "{\"title\":\"Private conversation\"}"))
            .path("id")
            .asText();
    jdbc.update("INSERT INTO memberships(user_id,space_id) VALUES('ben','team-guide')");
    try {
      Browser ben = new Browser(port);
      ben.login("demo_ben");
      assertEquals(200, ben.send("GET", "/api/spaces/team-guide/documents", null).statusCode());
      assertEquals(
          0,
          tree(ben.send("GET", "/api/spaces/team-guide/conversations", null)).path("items").size());
      assertEquals(
          404, ben.send("GET", "/api/spaces/team-guide/conversations/" + cid, null).statusCode());
      assertEquals(
          404,
          ben.send(
                  "POST",
                  "/api/spaces/team-guide/conversations/" + cid + "/messages",
                  "{\"question\":\"Private history?\",\"mode\":\"rag\",\"searchMode\":\"bm25\"}")
              .statusCode());
    } finally {
      jdbc.update("DELETE FROM memberships WHERE user_id='ben' AND space_id='team-guide'");
    }
  }

  @Test
  void overlongQuestionIsRejectedBeforeUnconfiguredModelCheck() throws Exception {
    Browser b = new Browser(port);
    b.login("demo_anna");
    String cid =
        tree(b.send("POST", "/api/spaces/team-guide/conversations", "{\"title\":\"Bounds\"}"))
            .path("id")
            .asText();
    String request =
        JSON.writeValueAsString(
            Map.of("question", "a".repeat(1001), "mode", "rag", "searchMode", "bm25"));
    assertEquals(
        422,
        b.send("POST", "/api/spaces/team-guide/conversations/" + cid + "/messages", request)
            .statusCode());
    assertEquals(
        0,
        tree(b.send("GET", "/api/spaces/team-guide/conversations/" + cid, null))
            .path("turns")
            .size());
  }

  static JsonNode tree(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }

  static final class Browser {
    final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    final String base;
    String csrf;

    Browser(int port) {
      base = "http://127.0.0.1:" + port;
    }

    HttpResponse<String> send(String method, String path, String body) throws Exception {
      return send(method, path, body, true, "application/json", null);
    }

    HttpResponse<String> send(
        String method, String path, String body, boolean token, String type, String origin)
        throws Exception {
      var req = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", type);
      if (token && csrf != null) req.header("X-CSRF-TOKEN", csrf);
      if (origin != null) req.header("Origin", origin);
      return client.send(
          req.method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    JsonNode session() throws Exception {
      var res = send("GET", "/api/session", null);
      assertEquals(200, res.statusCode(), res.body());
      var node = tree(res);
      csrf = node.path("csrf").path("token").asText();
      assertFalse(csrf.isBlank());
      return node;
    }

    void login(String user) throws Exception {
      session();
      var res =
          send(
              "POST",
              "/api/login",
              "username="
                  + user
                  + "&password="
                  + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8),
              true,
              "application/x-www-form-urlencoded",
              null);
      assertEquals(200, res.statusCode(), res.body());
      session();
    }

    HttpResponse<String> upload(
        String method, String sid, String did, Integer version, String filename, String content)
        throws Exception {
      String boundary = "test-" + UUID.randomUUID();
      String body =
          "--"
              + boundary
              + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
              + filename
              + "\"\r\nContent-Type: application/octet-stream\r\n\r\n"
              + content
              + "\r\n";
      if (version != null)
        body +=
            "--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"version\"\r\n\r\n"
                + version
                + "\r\n";
      body += "--" + boundary + "--\r\n";
      return send(
          method,
          "/api/spaces/" + sid + "/documents" + (did == null ? "" : "/" + did),
          body,
          true,
          "multipart/form-data; boundary=" + boundary,
          null);
    }
  }
}

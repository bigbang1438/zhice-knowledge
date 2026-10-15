package com.zhice;

import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
class SessionController {
  private final JdbcTemplate jdbc;
  private final KnowledgeEngine engine;

  SessionController(JdbcTemplate jdbc, KnowledgeEngine engine) {
    this.jdbc = jdbc;
    this.engine = engine;
  }

  @GetMapping("/api/session")
  Map<String, Object> session(Principal principal, CsrfToken csrf) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(
        "user",
        principal == null
            ? null
            : jdbc
                .query(
                    "SELECT id,display_name FROM app_users WHERE username=?",
                    (rs, n) -> Map.of("id", rs.getString(1), "name", rs.getString(2)),
                    principal.getName())
                .stream()
                .findFirst()
                .orElse(null));
    result.put("csrf", Map.of("headerName", csrf.getHeaderName(), "token", csrf.getToken()));
    result.put(
        "models",
        Map.of(
            "chatConfigured",
            engine.chatConfigured(),
            "embeddingConfigured",
            engine.embeddingConfigured()));
    return result;
  }
}

package com.zhice;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

final class ApiErrors {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  static Map<String, Object> body(String code, String message) {
    return Map.of("error", Map.of("code", code, "message", message));
  }

  static void write(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(JSON.writeValueAsString(body(code, message)));
  }
}

package com.zhice;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local demo boundary: Host/Origin checks complement CSRF and prevent DNS rebinding. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class LocalRequestFilter extends OncePerRequestFilter {
  private static final Set<String> HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    try {
      URI host = URI.create("http://" + request.getHeader("Host"));
      if (host.getRawUserInfo() != null
          || !HOSTS.contains(host.getHost())
          || effectivePort(host) != request.getLocalPort()) throw new IllegalArgumentException();
      String origin = request.getHeader("Origin");
      if (origin != null) {
        URI source = URI.create(origin);
        if (!"http".equals(source.getScheme())
            || source.getRawUserInfo() != null
            || !host.getHost().equalsIgnoreCase(source.getHost())
            || effectivePort(source) != request.getLocalPort()
            || !(source.getRawPath() == null || source.getRawPath().isEmpty()))
          throw new IllegalArgumentException();
      }
    } catch (RuntimeException invalid) {
      ApiErrors.write(response, 403, "invalid_origin", "仅允许本机同源访问");
      return;
    }
    String contentType = request.getContentType();
    if (contentType != null
        && contentType
            .toLowerCase(java.util.Locale.ROOT)
            .startsWith("application/x-www-form-urlencoded")) {
      if (request.getContentLengthLong() > 8192) {
        ApiErrors.write(response, 413, "request_too_large", "请求内容超过大小限制");
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (request.getRequestURI().startsWith("/api/")
        && contentType != null
        && !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) {
      int limit = 65536;
      byte[] data = request.getInputStream().readNBytes(limit + 1);
      if (data.length > limit) {
        ApiErrors.write(response, 413, "request_too_large", "请求内容超过大小限制");
        return;
      }
      chain.doFilter(new BufferedRequest(request, data), response);
    } else chain.doFilter(request, response);
  }

  private static int effectivePort(URI uri) {
    return uri.getPort() < 0 ? 80 : uri.getPort();
  }

  private static final class BufferedRequest extends HttpServletRequestWrapper {
    private final byte[] bytes;

    BufferedRequest(HttpServletRequest request, byte[] bytes) {
      super(request);
      this.bytes = bytes;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream input = new ByteArrayInputStream(bytes);
      return new ServletInputStream() {
        public int read() {
          return input.read();
        }

        public boolean isFinished() {
          return input.available() == 0;
        }

        public boolean isReady() {
          return true;
        }

        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("Synchronous requests only");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(
          new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}

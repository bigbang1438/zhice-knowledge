package com.zhice;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
class HttpErrors {
  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<Map<String, Object>> missing() {
    return ResponseEntity.status(404).body(ApiErrors.body("not_found", "未找到所请求的资源"));
  }

  @ExceptionHandler(KnowledgeException.class)
  ResponseEntity<Map<String, Object>> known(KnowledgeException e) {
    return ResponseEntity.status(e.status()).body(ApiErrors.body(e.code(), e.getMessage()));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<Map<String, Object>> large() {
    return ResponseEntity.status(413).body(ApiErrors.body("upload_too_large", "文件不能超过 2 MiB"));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    MultipartException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class
  })
  ResponseEntity<Map<String, Object>> bad() {
    return ResponseEntity.unprocessableContent()
        .body(ApiErrors.body("invalid_request", "请求格式或参数不正确"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> other(Exception e) {
    org.slf4j.LoggerFactory.getLogger(HttpErrors.class)
        .warn("Unhandled request failure: {}", e.getClass().getName());
    return ResponseEntity.internalServerError()
        .body(ApiErrors.body("internal_error", "操作失败，请稍后重试"));
  }
}

package com.zhice;

/** Carries an HTTP-safe, public error; internal causes are never sent to clients. */
public final class KnowledgeException extends RuntimeException {
  private final int status;
  private final String code;

  public KnowledgeException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}

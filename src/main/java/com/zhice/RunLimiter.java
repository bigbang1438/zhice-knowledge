package com.zhice;

import java.util.Set;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Nonblocking admission, so requests cannot create an unbounded model queue. */
@Component
class RunLimiter {
  private final Semaphore slots = new Semaphore(2);
  private final Set<String> answerOwners = ConcurrentHashMap.newKeySet();

  Permit enter(String answerOwner) {
    if (answerOwner != null && !answerOwners.add(answerOwner))
      throw new KnowledgeException(429, "answer_in_progress", "当前账号已有问答正在生成，请稍后重试");
    if (!slots.tryAcquire()) {
      if (answerOwner != null) answerOwners.remove(answerOwner);
      throw new KnowledgeException(503, "model_busy", "模型任务繁忙，请稍后重试");
    }
    return new Permit(answerOwner);
  }

  final class Permit implements AutoCloseable {
    private final String owner;
    private boolean closed;

    Permit(String owner) {
      this.owner = owner;
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        if (owner != null) answerOwners.remove(owner);
        slots.release();
      }
    }
  }
}

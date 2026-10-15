package com.zhice;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class KnowledgeEngineTest {
  @Test
  void validatesNullModesAsClientErrors() {
    assertThrows(KnowledgeException.class, () -> engine.search(fixtures(), "退款", null, 5));
    assertThrows(
        KnowledgeException.class, () -> engine.answer(fixtures(), "退款", List.of(), null, "bm25"));
    assertThrows(
        KnowledgeException.class, () -> engine.answer(fixtures(), "退款", List.of(), "rag", null));
  }

  @Test
  void agentCannotClaimInsufficientWithoutActuallySearching() {
    fake.replies.add(new ModelGateway.Message("assistant", "我不知道。"));
    var ex =
        assertThrows(
            KnowledgeException.class,
            () -> engine.answer(fixtures(), "退款", List.of(), "agent", "bm25"));
    assertEquals("MODEL_NO_RETRIEVAL", ex.code());
  }

  static class LocalFake implements ModelGateway {
    final Deque<Message> replies = new ArrayDeque<>();
    List<Message> lastMessages;
    boolean embedding;

    public boolean chatConfigured() {
      return true;
    }

    public boolean embeddingConfigured() {
      return embedding;
    }

    public List<List<Double>> embed(List<String> texts, Duration timeout) {
      return texts.stream().map(t -> t.contains("退款") ? List.of(1d, 0d) : List.of(0d, 1d)).toList();
    }

    public Message chat(List<Message> messages, List<Map<String, Object>> tools, Duration timeout) {
      lastMessages = List.copyOf(messages);
      return replies.isEmpty() ? new Message("assistant", "退款期限为七天。[1]") : replies.removeFirst();
    }
  }

  private final LocalFake fake = new LocalFake();
  private final KnowledgeEngine engine = new KnowledgeEngine(fake);

  private List<KnowledgeEngine.Chunk> fixtures() {
    return List.of(
        new KnowledgeEngine.Chunk(
            "policy:p1:l2", "policy", "退款.md", 1, 2, 2, "退款期限为七天，退款申请需要订单号。", List.of(1d, 0d)),
        new KnowledgeEngine.Chunk(
            "leave:p1:l1", "leave", "休假.txt", 1, 1, 1, "年假通过人事系统申请。", List.of(0d, 1d)));
  }

  // Removing source locations or accepting undecodable binary text would break import evidence.
  @Test
  void parsesUtf8TextWithOriginalLineLocations() {
    var chunks =
        engine.prepare(
            "doc", "手册.md", "\uFEFF# 手册\n\n退款期限为七天。\n申请需要订单号。".getBytes(StandardCharsets.UTF_8));
    assertFalse(chunks.isEmpty());
    assertEquals("doc", chunks.getFirst().documentId());
    assertEquals(1, chunks.getFirst().lineStart());
    assertEquals(4, chunks.getFirst().lineEnd());
    assertTrue(chunks.getFirst().text().contains("退款期限为七天。"));
    assertTrue(chunks.getFirst().vector().isEmpty());
  }

  @Test
  void rejectsPathsMalformedUtf8EmptyScannedAndOversizeFiles() {
    assertThrows(
        KnowledgeException.class, () -> engine.prepare("a", "../secret.txt", "abc".getBytes()));
    assertThrows(
        KnowledgeException.class,
        () -> engine.prepare("a", "a.txt", new byte[] {(byte) 0xC3, 0x28}));
    assertThrows(KnowledgeException.class, () -> engine.prepare("a", "a.txt", "  \n".getBytes()));
    assertThrows(
        KnowledgeException.class, () -> engine.prepare("a", "a.pdf", "not pdf".getBytes()));
    assertThrows(
        KnowledgeException.class,
        () -> engine.prepare("a", "a.txt", new byte[2 * 1024 * 1024 + 1]));
  }

  @Test
  void ranksChineseByBm25AndReturnsNoMatchesForUnknownTerms() {
    var found = engine.search(fixtures(), "退款期限", "bm25", 5);
    assertEquals("policy:p1:l2", found.getFirst().id());
    assertEquals(2, found.getFirst().lineStart());
    assertTrue(found.getFirst().score() > 0);
    assertTrue(engine.search(fixtures(), "量子隧穿", "bm25", 5).isEmpty());
  }

  @Test
  void usesConfiguredRealVectorsAndDoesNotInventMissingEmbeddings() {
    assertThrows(KnowledgeException.class, () -> engine.search(fixtures(), "退款", "vector", 5));
    fake.embedding = true;
    assertEquals("policy:p1:l2", engine.search(fixtures(), "退款", "vector", 5).getFirst().id());
    assertEquals("policy:p1:l2", engine.search(fixtures(), "退款", "hybrid", 5).getFirst().id());
    assertThrows(
        KnowledgeException.class,
        () ->
            engine.search(
                List.of(new KnowledgeEngine.Chunk("x", "d", "x.txt", 1, 1, 1, "退款", List.of())),
                "退款",
                "vector",
                5));
  }

  @Test
  void validatesModesAndLimitsBeforeSearching() {
    assertThrows(KnowledgeException.class, () -> engine.search(fixtures(), "退款", "other", 5));
    assertThrows(KnowledgeException.class, () -> engine.search(fixtures(), "退款", "bm25", 0));
    assertThrows(KnowledgeException.class, () -> engine.search(fixtures(), "退款", "bm25", 11));
    assertThrows(KnowledgeException.class, () -> engine.search(fixtures(), " ", "bm25", 5));
  }

  @Test
  void groundsRagAndKeepsOnlySixHistoryTurns() {
    var history = new ArrayList<KnowledgeEngine.History>();
    for (int i = 0; i < 9; i++)
      history.add(new KnowledgeEngine.History("old-question-" + i, "old-answer-" + i));
    var result = engine.answer(fixtures(), "退款期限", history, "rag", "bm25");
    assertEquals("answered", result.status());
    assertEquals("policy:p1:l2", result.sources().getFirst().id());
    assertFalse(fake.lastMessages.toString().contains("old-question-0"));
    assertTrue(fake.lastMessages.toString().contains("old-question-8"));
  }

  @Test
  void rejectsUnsourcedAndOutOfRangeModelCitations() {
    fake.replies.add(new ModelGateway.Message("assistant", "退款七天。"));
    assertThrows(
        KnowledgeException.class, () -> engine.answer(fixtures(), "退款", List.of(), "rag", "bm25"));
    fake.replies.add(new ModelGateway.Message("assistant", "退款七天。[2]"));
    assertThrows(
        KnowledgeException.class, () -> engine.answer(fixtures(), "退款", List.of(), "rag", "bm25"));
  }

  @Test
  void noRetrievalIsAnExplicitInsufficientReplyWithoutModelFallback() {
    var result = engine.answer(fixtures(), "量子隧穿", List.of(), "rag", "bm25");
    assertEquals("insufficient", result.status());
    assertTrue(result.sources().isEmpty());
    assertNull(fake.lastMessages);
  }

  @Test
  void agentSearchAndReadOnlyExposePreviouslyRetrievedPassages() {
    fake.replies.add(
        new ModelGateway.Message(
            "assistant",
            "",
            List.of(new ModelGateway.ToolCall("a", "search_knowledge", Map.of("query", "退款")))));
    fake.replies.add(
        new ModelGateway.Message(
            "assistant",
            "",
            List.of(
                new ModelGateway.ToolCall(
                    "b", "read_passage", Map.of("source_id", "policy:p1:l2")))));
    fake.replies.add(new ModelGateway.Message("assistant", "退款期限为七天。[1]"));
    var result = engine.answer(fixtures(), "退款期限", List.of(), "agent", "bm25");
    assertEquals("answered", result.status());
    assertEquals(
        List.of("search_knowledge", "read_passage"),
        result.trace().stream().map(KnowledgeEngine.Trace::tool).toList());
    assertEquals("policy:p1:l2", result.sources().getFirst().id());
  }

  @Test
  void agentRejectsUnknownToolsExtraParametersAndUnreadSources() {
    for (var call :
        List.of(
            new ModelGateway.ToolCall("a", "shell", Map.of("command", "ls")),
            new ModelGateway.ToolCall(
                "b", "search_knowledge", Map.of("query", "退款", "space", "other")),
            new ModelGateway.ToolCall("c", "read_passage", Map.of("source_id", "leave:p1:l1")))) {
      fake.replies.clear();
      fake.replies.add(new ModelGateway.Message("assistant", "", List.of(call)));
      assertThrows(
          KnowledgeException.class,
          () -> engine.answer(fixtures(), "退款", List.of(), "agent", "bm25"));
    }
  }

  @Test
  void repeatedToolsCannotEscapeSharedEightStepBudget() {
    for (int i = 0; i < 10; i++)
      fake.replies.add(
          new ModelGateway.Message(
              "assistant",
              "",
              List.of(
                  new ModelGateway.ToolCall("c" + i, "search_knowledge", Map.of("query", "退款")))));
    var ex =
        assertThrows(
            KnowledgeException.class,
            () -> engine.answer(fixtures(), "退款", List.of(), "agent", "bm25"));
    assertEquals("MODEL_BUDGET", ex.code());
    assertTrue(fake.replies.size() >= 6);
  }
}

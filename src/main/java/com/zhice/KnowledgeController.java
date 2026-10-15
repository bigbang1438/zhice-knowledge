package com.zhice;

import java.io.IOException;
import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/spaces")
class KnowledgeController {
  record NewConversation(String title) {}

  record NewMessage(String question, String mode, String searchMode) {}

  private final KnowledgeStore store;
  private final KnowledgeEngine engine;
  private final RunLimiter limiter;

  KnowledgeController(KnowledgeStore store, KnowledgeEngine engine, RunLimiter limiter) {
    this.store = store;
    this.engine = engine;
    this.limiter = limiter;
  }

  @GetMapping
  Map<String, Object> spaces(Principal actor) {
    return Map.of("items", store.spaces(actor.getName()));
  }

  @GetMapping("/{sid}/documents")
  Map<String, Object> documents(Principal actor, @PathVariable String sid) {
    return Map.of("items", store.documents(actor.getName(), sid));
  }

  @PostMapping(path = "/{sid}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  KnowledgeStore.Document create(
      Principal actor, @PathVariable String sid, @RequestParam MultipartFile file)
      throws IOException {
    String filename = filename(file);
    var snapshot = store.snapshot(actor.getName(), sid);
    byte[] bytes = bytes(file);
    String did = UUID.randomUUID().toString();
    try (var permit = limiter.enter(null)) {
      var chunks = engine.prepare(did, filename, bytes);
      return store.commitDocument(
          actor.getName(), sid, did, null, snapshot.revision(), filename, chunks);
    }
  }

  @PutMapping(path = "/{sid}/documents/{did}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  KnowledgeStore.Document replace(
      Principal actor,
      @PathVariable String sid,
      @PathVariable String did,
      @RequestParam long version,
      @RequestParam MultipartFile file)
      throws IOException {
    if (version < 1) throw invalid();
    String filename = filename(file);
    var doc = store.document(actor.getName(), sid, did);
    if (doc.version() != version)
      throw new KnowledgeException(409, "knowledge_changed", "文档版本已变化，请刷新后重试");
    var snapshot = store.snapshot(actor.getName(), sid);
    byte[] bytes = bytes(file);
    try (var permit = limiter.enter(null)) {
      var chunks = engine.prepare(did, filename, bytes);
      return store.commitDocument(
          actor.getName(), sid, did, version, snapshot.revision(), filename, chunks);
    }
  }

  @DeleteMapping("/{sid}/documents/{did}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(
      Principal actor,
      @PathVariable String sid,
      @PathVariable String did,
      @RequestParam long version) {
    if (version < 1) throw invalid();
    store.deleteDocument(actor.getName(), sid, did, version);
  }

  @GetMapping("/{sid}/search")
  Map<String, Object> search(
      Principal actor,
      @PathVariable String sid,
      @RequestParam String q,
      @RequestParam(defaultValue = "hybrid") String mode,
      @RequestParam(defaultValue = "5") int limit) {
    q = text(q, 1000);
    searchMode(mode);
    if (limit < 1 || limit > 10) throw invalid();
    var snapshot = store.snapshot(actor.getName(), sid);
    if (!mode.equals("bm25") && !engine.embeddingConfigured())
      throw new KnowledgeException(503, "embedding_not_configured", "尚未配置向量模型，请使用 BM25 检索");
    try (var permit = limiter.enter(null)) {
      return Map.of(
          "sources",
          engine.search(snapshot.chunks(), q, mode, limit),
          "revision",
          snapshot.revision(),
          "mode",
          mode);
    }
  }

  @GetMapping("/{sid}/conversations")
  Map<String, Object> conversations(Principal actor, @PathVariable String sid) {
    return Map.of("items", store.conversations(actor.getName(), sid));
  }

  @PostMapping("/{sid}/conversations")
  @ResponseStatus(HttpStatus.CREATED)
  KnowledgeStore.Conversation createConversation(
      Principal actor, @PathVariable String sid, @RequestBody NewConversation body) {
    if (body == null) throw invalid();
    return store.createConversation(actor.getName(), sid, text(body.title(), 100));
  }

  @GetMapping("/{sid}/conversations/{cid}")
  Map<String, Object> conversation(
      Principal actor, @PathVariable String sid, @PathVariable String cid) {
    return store.conversation(actor.getName(), sid, cid);
  }

  @PostMapping("/{sid}/conversations/{cid}/messages")
  @ResponseStatus(HttpStatus.CREATED)
  KnowledgeStore.Turn message(
      Principal actor,
      @PathVariable String sid,
      @PathVariable String cid,
      @RequestBody NewMessage body) {
    if (body == null) throw invalid();
    String question = text(body.question(), 1000);
    String mode = body.mode();
    if (!Set.of("rag", "agent").contains(mode == null ? "" : mode)) throw invalid();
    searchMode(body.searchMode());
    var snapshot = store.conversationSnapshot(actor.getName(), sid, cid);
    if (!engine.chatConfigured())
      throw new KnowledgeException(503, "chat_not_configured", "尚未配置对话模型，当前可使用资料检索");
    if (!body.searchMode().equals("bm25") && !engine.embeddingConfigured())
      throw new KnowledgeException(503, "embedding_not_configured", "尚未配置向量模型，请使用 BM25 检索");
    try (var permit = limiter.enter(actor.getName())) {
      var reply =
          engine.answer(
              snapshot.knowledge().chunks(), question, snapshot.history(), mode, body.searchMode());
      return store.commitTurn(
          actor.getName(),
          sid,
          cid,
          snapshot.knowledge().revision(),
          snapshot.turnCount(),
          question,
          reply);
    }
  }

  private static void searchMode(String mode) {
    if (mode == null || !Set.of("bm25", "vector", "hybrid").contains(mode)) throw invalid();
  }

  private static String text(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\r'))
      throw invalid();
    return value.strip();
  }

  private static String filename(MultipartFile file) {
    String name = file.getOriginalFilename();
    if (name == null
        || name.isBlank()
        || name.length() > 100
        || name.contains("/")
        || name.contains("\\")
        || name.contains("..")
        || name.codePoints().anyMatch(Character::isISOControl))
      throw new KnowledgeException(422, "invalid_filename", "文件名不正确，最多 100 个字符且不能包含路径");
    String lower = name.toLowerCase(Locale.ROOT);
    if (!lower.endsWith(".md") && !lower.endsWith(".txt") && !lower.endsWith(".pdf"))
      throw new KnowledgeException(422, "unsupported_file", "只支持 UTF-8 Markdown、文本及可提取文字的 PDF");
    return name;
  }

  private static byte[] bytes(MultipartFile file) throws IOException {
    if (file.getSize() > 2 * 1024 * 1024)
      throw new KnowledgeException(413, "upload_too_large", "文件不能超过 2 MiB");
    byte[] data = file.getInputStream().readNBytes(2 * 1024 * 1024 + 1);
    if (data.length > 2 * 1024 * 1024)
      throw new KnowledgeException(413, "upload_too_large", "文件不能超过 2 MiB");
    return data;
  }

  private static KnowledgeException invalid() {
    return new KnowledgeException(422, "invalid_request", "请求参数不正确");
  }
}

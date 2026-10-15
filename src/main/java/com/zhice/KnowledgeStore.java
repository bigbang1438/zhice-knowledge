package com.zhice;

import static com.zhice.KnowledgeEngine.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Short transactions only: model and parsing work always happen in the caller. */
@Repository
class KnowledgeStore {
  record Space(String id, String name, long revision) {}

  record Document(
      String id,
      String filename,
      long version,
      int chunkCount,
      String indexMode,
      String createdAt) {}

  record Conversation(String id, String title, String createdAt) {}

  record Turn(
      String id,
      String question,
      String answer,
      List<Source> sources,
      List<Trace> trace,
      String status,
      String createdAt) {}

  record Snapshot(long revision, List<Chunk> chunks) {}

  record ConversationSnapshot(Snapshot knowledge, List<History> history, int turnCount) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  KnowledgeStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(manager);
  }

  private String userId(String username) {
    return jdbc
        .query("SELECT id FROM app_users WHERE username=?", (rs, n) -> rs.getString(1), username)
        .stream()
        .findFirst()
        .orElseThrow(() -> new KnowledgeException(401, "unauthorized", "请先登录"));
  }

  private void authorize(String user, String sid) {
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM memberships WHERE user_id=? AND space_id=?",
            Integer.class,
            user,
            sid)
        == 0) throw new KnowledgeException(403, "forbidden", "无权访问该资料库");
  }

  private Space lockSpace(String user, String sid) {
    authorize(user, sid);
    return jdbc
        .query(
            "SELECT id,name,revision FROM spaces WHERE id=? FOR UPDATE",
            (rs, n) -> new Space(rs.getString(1), rs.getString(2), rs.getLong(3)),
            sid)
        .stream()
        .findFirst()
        .orElseThrow(() -> missing());
  }

  List<Space> spaces(String username) {
    String uid = userId(username);
    return jdbc.query(
        "SELECT s.id,s.name,s.revision FROM spaces s JOIN memberships m ON m.space_id=s.id WHERE"
            + " m.user_id=? ORDER BY s.id",
        (rs, n) -> new Space(rs.getString(1), rs.getString(2), rs.getLong(3)),
        uid);
  }

  List<Document> documents(String username, String sid) {
    String uid = userId(username);
    authorize(uid, sid);
    return jdbc.query(
        "SELECT * FROM documents WHERE space_id=? ORDER BY created_at,id",
        (rs, n) -> document(rs),
        sid);
  }

  Document document(String username, String sid, String did) {
    String uid = userId(username);
    authorize(uid, sid);
    return readDocument(sid, did);
  }

  private Document readDocument(String sid, String did) {
    return jdbc
        .query(
            "SELECT * FROM documents WHERE id=? AND space_id=?", (rs, n) -> document(rs), did, sid)
        .stream()
        .findFirst()
        .orElseThrow(() -> missing());
  }

  Snapshot snapshot(String username, String sid) {
    String uid = userId(username);
    return tx.execute(
        status -> {
          Space space = lockSpace(uid, sid);
          return new Snapshot(space.revision(), chunks(sid));
        });
  }

  private List<Chunk> chunks(String sid) {
    return jdbc.query(
        "SELECT c.* FROM chunks c JOIN documents d ON c.document_id=d.id WHERE d.space_id=? ORDER"
            + " BY d.created_at,d.id,c.page_number,c.line_start,c.id",
        (rs, n) ->
            new Chunk(
                rs.getString("id"),
                rs.getString("document_id"),
                rs.getString("filename"),
                rs.getInt("page_number"),
                rs.getInt("line_start"),
                rs.getInt("line_end"),
                rs.getString("text_content"),
                JSON.readValue(rs.getString("vector_json"), new TypeReference<List<Double>>() {})),
        sid);
  }

  Document commitDocument(
      String username,
      String sid,
      String did,
      Long expectedVersion,
      long expectedRevision,
      String filename,
      List<Chunk> chunks) {
    String uid = userId(username);
    return tx.execute(
        status -> {
          Space space = lockSpace(uid, sid);
          if (space.revision() != expectedRevision) throw changed();
          int oldCount = 0;
          String createdAt = Instant.now().toString();
          long version = 1;
          if (expectedVersion != null) {
            Document old = readDocument(sid, did);
            if (old.version() != expectedVersion) throw changed();
            oldCount = old.chunkCount();
            createdAt = old.createdAt();
            version = old.version() + 1;
          } else if (jdbc.queryForObject(
                  "SELECT COUNT(*) FROM documents WHERE space_id=?", Integer.class, sid)
              >= 80) throw new KnowledgeException(422, "document_limit", "每个资料库最多 80 份文档");
          Integer currentCount =
              jdbc.queryForObject(
                  "SELECT COALESCE(SUM(chunk_count),0) FROM documents WHERE space_id=?",
                  Integer.class,
                  sid);
          if (chunks.isEmpty() || currentCount - oldCount + chunks.size() > 2000)
            throw new KnowledgeException(422, "chunk_limit", "资料库文本分段数量超过限制");
          String indexMode =
              chunks.stream().allMatch(c -> c.vector() != null && !c.vector().isEmpty())
                  ? "hybrid"
                  : "bm25";
          if (expectedVersion == null)
            jdbc.update(
                "INSERT INTO"
                    + " documents(id,space_id,filename,version,chunk_count,index_mode,created_at)"
                    + " VALUES(?,?,?,?,?,?,?)",
                did,
                sid,
                filename,
                version,
                chunks.size(),
                indexMode,
                createdAt);
          else {
            jdbc.update("DELETE FROM chunks WHERE document_id=?", did);
            jdbc.update(
                "UPDATE documents SET filename=?,version=?,chunk_count=?,index_mode=? WHERE id=?"
                    + " AND space_id=?",
                filename,
                version,
                chunks.size(),
                indexMode,
                did,
                sid);
          }
          for (Chunk c : chunks) {
            if (!did.equals(c.documentId()) || !filename.equals(c.filename()))
              throw new KnowledgeException(422, "invalid_index", "文档索引不一致");
            jdbc.update(
                "INSERT INTO"
                    + " chunks(id,document_id,filename,page_number,line_start,line_end,text_content,vector_json)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                c.id(),
                did,
                filename,
                c.page(),
                c.lineStart(),
                c.lineEnd(),
                c.text(),
                JSON.writeValueAsString(c.vector()));
          }
          jdbc.update("UPDATE spaces SET revision=revision+1 WHERE id=?", sid);
          return new Document(did, filename, version, chunks.size(), indexMode, createdAt);
        });
  }

  void deleteDocument(String username, String sid, String did, long version) {
    String uid = userId(username);
    tx.executeWithoutResult(
        status -> {
          lockSpace(uid, sid);
          Document doc = readDocument(sid, did);
          if (doc.version() != version) throw changed();
          jdbc.update("DELETE FROM documents WHERE id=? AND space_id=?", did, sid);
          jdbc.update("UPDATE spaces SET revision=revision+1 WHERE id=?", sid);
        });
  }

  List<Conversation> conversations(String username, String sid) {
    String uid = userId(username);
    authorize(uid, sid);
    return jdbc.query(
        "SELECT id,title,created_at FROM conversations WHERE user_id=? AND space_id=? ORDER BY"
            + " created_at DESC,id",
        (rs, n) -> new Conversation(rs.getString(1), rs.getString(2), rs.getString(3)),
        uid,
        sid);
  }

  Conversation createConversation(String username, String sid, String title) {
    String uid = userId(username);
    return tx.execute(
        status -> {
          lockSpace(uid, sid);
          if (jdbc.queryForObject(
                  "SELECT COUNT(*) FROM conversations WHERE user_id=? AND space_id=?",
                  Integer.class,
                  uid,
                  sid)
              >= 100) throw new KnowledgeException(422, "conversation_limit", "请使用已有会话，当前会话数量已达上限");
          Conversation conversation =
              new Conversation(UUID.randomUUID().toString(), title, Instant.now().toString());
          jdbc.update(
              "INSERT INTO conversations(id,space_id,user_id,title,created_at) VALUES(?,?,?,?,?)",
              conversation.id(),
              sid,
              uid,
              title,
              conversation.createdAt());
          return conversation;
        });
  }

  private Conversation ownedConversation(String uid, String sid, String cid) {
    authorize(uid, sid);
    return jdbc
        .query(
            "SELECT id,title,created_at FROM conversations WHERE id=? AND space_id=? AND user_id=?",
            (rs, n) -> new Conversation(rs.getString(1), rs.getString(2), rs.getString(3)),
            cid,
            sid,
            uid)
        .stream()
        .findFirst()
        .orElseThrow(() -> missing());
  }

  Map<String, Object> conversation(String username, String sid, String cid) {
    String uid = userId(username);
    return tx.execute(
        status -> {
          lockSpace(uid, sid);
          Conversation c = ownedConversation(uid, sid, cid);
          return Map.of("id", c.id(), "title", c.title(), "turns", turns(cid));
        });
  }

  private List<Turn> turns(String cid) {
    return jdbc.query(
        "SELECT * FROM turns WHERE conversation_id=? ORDER BY sequence_number",
        (rs, n) ->
            new Turn(
                rs.getString("id"),
                rs.getString("question"),
                rs.getString("answer"),
                JSON.readValue(rs.getString("sources_json"), new TypeReference<List<Source>>() {}),
                JSON.readValue(rs.getString("trace_json"), new TypeReference<List<Trace>>() {}),
                rs.getString("status"),
                rs.getString("created_at")),
        cid);
  }

  ConversationSnapshot conversationSnapshot(String username, String sid, String cid) {
    String uid = userId(username);
    return tx.execute(
        status -> {
          Space space = lockSpace(uid, sid);
          ownedConversation(uid, sid, cid);
          List<Turn> prior = turns(cid);
          if (prior.size() >= 200)
            throw new KnowledgeException(422, "turn_limit", "当前会话已达上限，请新建会话");
          List<History> history =
              prior.subList(Math.max(0, prior.size() - 6), prior.size()).stream()
                  .map(t -> new History(t.question(), t.answer()))
                  .toList();
          return new ConversationSnapshot(
              new Snapshot(space.revision(), chunks(sid)), history, prior.size());
        });
  }

  Turn commitTurn(
      String username,
      String sid,
      String cid,
      long revision,
      int previousTurnCount,
      String question,
      Reply reply) {
    String uid = userId(username);
    return tx.execute(
        status -> {
          Space space = lockSpace(uid, sid);
          ownedConversation(uid, sid, cid);
          if (space.revision() != revision) throw changed();
          int count =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM turns WHERE conversation_id=?", Integer.class, cid);
          if (count != previousTurnCount) throw changed();
          Turn turn =
              new Turn(
                  UUID.randomUUID().toString(),
                  question,
                  reply.answer(),
                  reply.sources(),
                  reply.trace(),
                  reply.status(),
                  Instant.now().toString());
          jdbc.update(
              "INSERT INTO"
                  + " turns(id,conversation_id,sequence_number,question,answer,sources_json,trace_json,status,created_at)"
                  + " VALUES(?,?,?,?,?,?,?,?,?)",
              turn.id(),
              cid,
              count + 1,
              question,
              turn.answer(),
              JSON.writeValueAsString(turn.sources()),
              JSON.writeValueAsString(turn.trace()),
              turn.status(),
              turn.createdAt());
          return turn;
        });
  }

  private static Document document(ResultSet rs) throws SQLException {
    return new Document(
        rs.getString("id"),
        rs.getString("filename"),
        rs.getLong("version"),
        rs.getInt("chunk_count"),
        rs.getString("index_mode"),
        rs.getString("created_at"));
  }

  private static KnowledgeException missing() {
    return new KnowledgeException(404, "not_found", "未找到所请求的资源");
  }

  private static KnowledgeException changed() {
    return new KnowledgeException(409, "knowledge_changed", "资料或会话版本已变化，请刷新后重试");
  }
}

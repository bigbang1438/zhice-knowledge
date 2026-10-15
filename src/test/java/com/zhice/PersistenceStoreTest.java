package com.zhice;

import static com.zhice.KnowledgeEngine.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class PersistenceStoreTest {
  @TempDir Path directory;

  @Test
  void documentVectorsAndTurnsSurviveFreshConnectionsToFileDatabase() {
    String url =
        "jdbc:h2:file:" + directory.resolve("store").toAbsolutePath().toString().replace('\\', '/');
    var first = database(url);
    seed(first);
    var store = store(first);
    store.commitDocument(
        "demo_fixture",
        "space",
        "doc",
        null,
        0,
        "policy.md",
        List.of(
            new Chunk(
                "chunk-old",
                "doc",
                "policy.md",
                1,
                1,
                1,
                "Records remain for ninety days.",
                List.of(0.25, 0.75))));
    var conversation = store.createConversation("demo_fixture", "space", "Retention");
    store.commitTurn(
        "demo_fixture",
        "space",
        conversation.id(),
        1,
        0,
        "How long?",
        new Reply(
            "Ninety days. [1]",
            List.of(
                new Source(
                    "chunk-old",
                    "doc",
                    "policy.md",
                    1,
                    1,
                    1,
                    "Records remain for ninety days.",
                    1.0)),
            List.of(
                new Trace(
                    1, "search_knowledge", java.util.Map.of("query", "retention"), "1 source", 1)),
            "answered"));
    var restored = store(database(url));
    assertEquals(1, restored.documents("demo_fixture", "space").size());
    assertEquals("hybrid", restored.documents("demo_fixture", "space").get(0).indexMode());
    var snapshot = restored.snapshot("demo_fixture", "space");
    assertEquals(1, snapshot.revision());
    assertEquals(List.of(0.25, 0.75), snapshot.chunks().get(0).vector());
    var history = restored.conversationSnapshot("demo_fixture", "space", conversation.id());
    assertEquals(1, history.turnCount());
    assertEquals("How long?", history.history().get(0).question());
    assertEquals("Ninety days. [1]", history.history().get(0).answer());
  }

  @Test
  void failedChunkInsertRollsBackDeletedChunksDocumentVersionAndRevision() {
    String url = "jdbc:h2:mem:rollback-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    var db = database(url);
    seed(db);
    var store = store(db);
    store.commitDocument(
        "demo_fixture",
        "space",
        "doc",
        null,
        0,
        "policy.md",
        List.of(new Chunk("old", "doc", "policy.md", 1, 1, 1, "Original policy", List.of())));
    List<Chunk> badReplacement =
        List.of(
            new Chunk("duplicate", "doc", "policy.md", 1, 1, 1, "First new chunk", List.of()),
            new Chunk("duplicate", "doc", "policy.md", 1, 2, 2, "Second new chunk", List.of()));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            store.commitDocument(
                "demo_fixture", "space", "doc", 1L, 1, "policy.md", badReplacement));
    assertEquals(1, store.documents("demo_fixture", "space").get(0).version());
    var snapshot = store.snapshot("demo_fixture", "space");
    assertEquals(1, snapshot.revision());
    assertEquals(1, snapshot.chunks().size());
    assertEquals("Original policy", snapshot.chunks().get(0).text());
  }

  private DriverManagerDataSource database(String url) {
    var ds = new DriverManagerDataSource(url, "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
    return ds;
  }

  private KnowledgeStore store(DriverManagerDataSource ds) {
    return new KnowledgeStore(new JdbcTemplate(ds), new DataSourceTransactionManager(ds));
  }

  private void seed(DriverManagerDataSource ds) {
    var db = new JdbcTemplate(ds);
    db.update(
        "INSERT INTO app_users(id,username,display_name,password_hash)"
            + " VALUES('fixture','demo_fixture','Fixture','not-a-login-hash')");
    db.update("INSERT INTO spaces(id,name,revision) VALUES('space','Fixture',0)");
    db.update("INSERT INTO memberships(user_id,space_id) VALUES('fixture','space')");
  }
}

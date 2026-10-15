package com.zhice;

import java.util.*;

/** Small bounded corpus retrieval. RRF scores are ranks, not calibrated relevance probabilities. */
final class Retrieval {
  record Ranked(KnowledgeEngine.Chunk chunk, double score) {}

  static List<String> tokens(String input) {
    var out = new ArrayList<String>();
    var latin = new StringBuilder();
    var han = new StringBuilder();
    for (int cp : input.toLowerCase(Locale.ROOT).codePoints().toArray()) {
      if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
        flushLatin(latin, out);
        han.appendCodePoint(cp);
      } else {
        flushHan(han, out);
        if (Character.isLetterOrDigit(cp)) latin.appendCodePoint(cp);
        else flushLatin(latin, out);
      }
    }
    flushLatin(latin, out);
    flushHan(han, out);
    return out;
  }

  private static void flushLatin(StringBuilder b, List<String> out) {
    if (!b.isEmpty()) {
      out.add(b.toString());
      b.setLength(0);
    }
  }

  private static void flushHan(StringBuilder b, List<String> out) {
    int[] cps = b.toString().codePoints().toArray();
    for (int i = 0; i < cps.length; i++) {
      out.add(new String(cps, i, 1));
      if (i + 1 < cps.length) out.add(new String(cps, i, 2));
    }
    b.setLength(0);
  }

  static List<Ranked> bm25(List<KnowledgeEngine.Chunk> chunks, String query) {
    var terms = new HashSet<>(tokens(query));
    var documents = new ArrayList<Map<String, Integer>>();
    var df = new HashMap<String, Integer>();
    double length = 0;
    for (var c : chunks) {
      var freq = new HashMap<String, Integer>();
      for (String t : tokens(c.text())) freq.merge(t, 1, Integer::sum);
      documents.add(freq);
      length += freq.values().stream().mapToInt(i -> i).sum();
      for (String t : freq.keySet()) df.merge(t, 1, Integer::sum);
    }
    double avg = chunks.isEmpty() ? 1 : Math.max(1, length / chunks.size());
    var results = new ArrayList<Ranked>();
    for (int i = 0; i < chunks.size(); i++) {
      var tf = documents.get(i);
      int dl = tf.values().stream().mapToInt(v -> v).sum();
      double score = 0;
      for (String term : terms) {
        int f = tf.getOrDefault(term, 0);
        if (f == 0) continue;
        double idf = Math.log(1 + (chunks.size() - df.get(term) + 0.5) / (df.get(term) + 0.5));
        score += idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * dl / avg));
      }
      if (score > 0) results.add(new Ranked(chunks.get(i), score));
    }
    return sorted(results);
  }

  static List<Ranked> vector(List<KnowledgeEngine.Chunk> chunks, List<Double> query) {
    validateVector(query);
    var out = new ArrayList<Ranked>();
    for (var c : chunks) {
      if (c.vector() == null || c.vector().size() != query.size())
        throw new KnowledgeException(422, "INDEX_REBUILD_REQUIRED", "当前向量索引不完整或维度已变更，请重新索引文档");
      validateVector(c.vector());
      double dot = 0, a = 0, b = 0;
      for (int i = 0; i < query.size(); i++) {
        double x = query.get(i), y = c.vector().get(i);
        dot += x * y;
        a += x * x;
        b += y * y;
      }
      double similarity = dot / Math.sqrt(a * b);
      if (similarity > 0) out.add(new Ranked(c, similarity));
    }
    return sorted(out);
  }

  static void validateVector(List<Double> v) {
    if (v == null
        || v.isEmpty()
        || v.size() > 8192
        || v.stream().anyMatch(n -> n == null || !Double.isFinite(n))
        || v.stream().mapToDouble(n -> n * n).sum() <= 0)
      throw new KnowledgeException(502, "INVALID_EMBEDDING", "模型返回的向量无效");
  }

  static List<Ranked> rrf(List<Ranked> lexical, List<Ranked> semantic) {
    var scores = new HashMap<String, Double>();
    var chunks = new HashMap<String, KnowledgeEngine.Chunk>();
    for (var ranking : List.of(lexical, semantic)) {
      for (int i = 0; i < ranking.size(); i++) {
        var r = ranking.get(i);
        scores.merge(r.chunk().id(), 1d / (60 + i + 1), Double::sum);
        chunks.put(r.chunk().id(), r.chunk());
      }
    }
    var out = new ArrayList<Ranked>();
    scores.forEach((id, score) -> out.add(new Ranked(chunks.get(id), score)));
    return sorted(out);
  }

  private static List<Ranked> sorted(List<Ranked> rows) {
    return rows.stream()
        .sorted(
            Comparator.comparingDouble(Ranked::score).reversed().thenComparing(r -> r.chunk().id()))
        .toList();
  }
}

package com.zhice;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

/** Text PDFs only. Locations refer to extracted page lines, not guessed PDF layout. */
final class DocumentParser {
  List<KnowledgeEngine.Chunk> parse(String id, String filename, byte[] data) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}")) throw invalid("文档标识无效");
    if (filename == null
        || filename.isBlank()
        || filename.length() > 100
        || filename.contains("/")
        || filename.contains("\\")
        || filename.codePoints().anyMatch(c -> Character.isISOControl(c))) throw invalid("文件名无效");
    if (data == null || data.length == 0) throw invalid("文档为空");
    if (data.length > 2 * 1024 * 1024)
      throw new KnowledgeException(413, "FILE_TOO_LARGE", "文档不能超过2MiB");
    var pages = new ArrayList<String>();
    String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    if (ext.equals("pdf")) {
      try (var pdf = Loader.loadPDF(data)) {
        if (pdf.isEncrypted() || pdf.getNumberOfPages() > 100) throw invalid("PDF须未加密且不超过100页");
        var stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
          stripper.setStartPage(page);
          stripper.setEndPage(page);
          pages.add(stripper.getText(pdf));
        }
      } catch (KnowledgeException ex) {
        throw ex;
      } catch (Exception ex) {
        throw invalid("无法解析PDF文本，请使用未加密的文本PDF");
      }
    } else if (ext.equals("md") || ext.equals("txt")) {
      try {
        pages.add(
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
                .replaceFirst("^\\uFEFF", ""));
      } catch (CharacterCodingException ex) {
        throw invalid("TXT及Markdown须使用UTF-8编码");
      }
    } else throw invalid("仅支持PDF、Markdown及TXT");
    int total = pages.stream().mapToInt(String::length).sum();
    if (total > 400000) throw invalid("文档提取文本过长，请拆分上传");
    var chunks = new ArrayList<KnowledgeEngine.Chunk>();
    for (int pi = 0; pi < pages.size(); pi++) {
      String page = pages.get(pi).replace("\r\n", "\n").replace('\r', '\n');
      if (page.indexOf('\0') >= 0) throw invalid("文档含无效文本字符");
      String[] lines = page.split("\n", -1);
      int start = 0;
      while (start < lines.length) {
        while (start < lines.length && lines[start].isBlank()) start++;
        if (start == lines.length) break;
        int end = start;
        var text = new StringBuilder();
        while (end < lines.length && (text.length() < 800 || end == start)) {
          if (!text.isEmpty()) text.append('\n');
          text.append(lines[end]);
          end++;
        }
        String content = text.toString().stripTrailing();
        for (int offset = 0; offset < content.length(); offset += 1100) {
          String slice = content.substring(offset, Math.min(content.length(), offset + 1200));
          int seq = chunks.size() + 1;
          chunks.add(
              new KnowledgeEngine.Chunk(
                  id + ":p" + (pi + 1) + ":l" + (start + 1) + ":c" + seq,
                  id,
                  filename,
                  pi + 1,
                  start + 1,
                  end,
                  slice,
                  List.of()));
          if (chunks.size() > 2000) throw invalid("文档分段过多，请拆分上传");
        }
        start = end;
      }
    }
    if (chunks.isEmpty()) throw invalid(ext.equals("pdf") ? "PDF未提取到文本，扫描件暂不支持OCR" : "文档没有可索引文本");
    return List.copyOf(chunks);
  }

  private KnowledgeException invalid(String message) {
    return new KnowledgeException(422, "INVALID_DOCUMENT", message);
  }
}

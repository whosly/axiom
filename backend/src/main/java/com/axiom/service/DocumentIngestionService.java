package com.axiom.service;

import com.axiom.model.DocumentChunkRequest;
import com.axiom.model.KnowledgeRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 文档解析与分块服务 / Document parsing and chunking service.
 *
 * <p>把上传的 PDF/Word/Markdown/文本文件解析为纯文本，按语义边界（段落/标题）
 * 切分为带重叠的块，再归一化成 KnowledgeRequest 交给 KnowledgeService 入库。
 * / Parses uploaded PDF/Word/Markdown/text into plain text, splits into overlapping
 * chunks along semantic boundaries (paragraphs/headings), and normalizes them to
 * KnowledgeRequest for KnowledgeService ingestion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    /** 每块目标字符数 / Target characters per chunk. */
    private static final int CHUNK_SIZE = 600;
    /** 相邻块重叠字符数 / Overlap characters between chunks. */
    private static final int OVERLAP = 100;

    private final KnowledgeService knowledgeService;

    /**
     * 解析文件并入库 / Parse a file and ingest its chunks.
     *
     * @param file      上传的文件 / Uploaded file.
     * @param datasetId 关联数据集（可空）/ Associated dataset (nullable).
     * @return 入库统计 / Ingestion statistics.
     */
    public java.util.Map<String, Object> ingest(MultipartFile file, String datasetId) throws IOException {
        String name = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String text = extractText(file.getBytes(), name);
        if (text.isBlank()) throw new IOException("文档内容为空 / Document is empty");

        List<DocumentChunkRequest> chunks = chunk(text, name);
        // 关联数据集 ID 写入每个块 / Associate dataset ID with every chunk.
        if (datasetId != null && !datasetId.isBlank()) {
            for (DocumentChunkRequest c : chunks) c.setDatasetId(datasetId);
        }
        String sourceId = "document-" + UUID.randomUUID();
        List<KnowledgeRequest> requests = new ArrayList<>(chunks);
        int added = knowledgeService.addBatch(requests, sourceId);

        log.info("[DocumentIngestion] file={} chunks={} added={}", name, chunks.size(), added);
        return java.util.Map.of("sourceId", sourceId, "fileName", name,
                "chunks", chunks.size(), "added", added, "characters", text.length());
    }

    /** 按扩展名解析文本 / Extract plain text by file extension. */
    private String extractText(byte[] bytes, String fileName) throws IOException {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return parsePdf(bytes);
        if (lower.endsWith(".docx")) return parseDocx(bytes);
        if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".markdown")) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        throw new IOException("不支持的文档格式（支持 PDF/DOCX/MD/TXT）/ Unsupported format");
    }

    /** PDF 解析 / Parse PDF. */
    private String parsePdf(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /** Word(docx) 解析 / Parse DOCX. */
    private String parseDocx(byte[] bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) sb.append(t.trim()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 语义分块 / Semantic chunking.
     *
     * <p>优先按空行/段落边界切分，保证块尽量包含完整语义；块间保留 overlap 避免边界信息丢失。
     * / Prefer splitting on blank-line/paragraph boundaries so chunks keep complete semantics;
     * overlap between chunks avoids losing information at boundaries.
     */
    private List<DocumentChunkRequest> chunk(String text, String fileName) {
        List<DocumentChunkRequest> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder current = new StringBuilder();
        // 用单元素数组在 appendPiece 间传递计数器（Java int 按值传递，形参自增不影响调用方）。
        // / Pass the chunk counter through a one-element box: Java ints are passed by value,
        // so incrementing a parameter would never propagate back to the caller.
        int[] idx = {0};
        for (String para : paragraphs) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            // 单段落超上限时按句/字符二次切分，避免产生超出 embedding 有效长度的巨块。
            // / Oversized paragraphs are split further so no chunk exceeds the embedding
            // effective length.
            if (p.length() > CHUNK_SIZE) {
                for (String piece : splitOversized(p)) {
                    idx[0] = appendPiece(chunks, piece, fileName, idx[0], current);
                }
                continue;
            }
            idx[0] = appendPiece(chunks, p, fileName, idx[0], current);
        }
        if (current.length() > 0) addChunk(chunks, current.toString(), fileName, idx[0]);
        return chunks;
    }

    /**
     * 追加一个段落并按预算滚动出块，返回滚动后的块序号 / Append a paragraph, rolling chunks by
     * budget; returns the chunk index to continue with after a possible flush.
     */
    private int appendPiece(List<DocumentChunkRequest> chunks, String p, String fileName,
                            int idx, StringBuilder current) {
        int next = idx;
        if (current.length() + p.length() > CHUNK_SIZE && current.length() > 0) {
            addChunk(chunks, current.toString(), fileName, next);
            // 重叠：保留上一块尾部 / Overlap: keep tail of previous chunk.
            String tail = current.substring(Math.max(0, current.length() - OVERLAP));
            current.setLength(0);
            current.append(tail);
            next++;
        }
        // 触发滚动本段的 p 不能丢：滚动后追加到新块，下一轮再判超限。
        // / The paragraph that triggered a flush must not be dropped: append it to
        // the fresh chunk; the budget check happens on the next iteration.
        current.append(p).append("\n");
        return next;
    }

    /** 超长段落按句边界粗切，再兜底硬切 / Split an oversized paragraph by sentence, then hard-cut. */
    private List<String> splitOversized(String p) {
        List<String> pieces = new ArrayList<>();
        String[] sentences = p.split("(?<=[。！？!?\\.])\\s*");
        StringBuilder current = new StringBuilder();
        for (String s : sentences) {
            if (s.isBlank()) continue;
            if (s.length() > CHUNK_SIZE) {
                // 单句仍超长：硬切 / A single sentence still oversized: hard-cut it.
                for (int i = 0; i < s.length(); i += CHUNK_SIZE) {
                    flushPiece(pieces, current);
                    pieces.add(s.substring(i, Math.min(i + CHUNK_SIZE, s.length())));
                }
                continue;
            }
            if (current.length() + s.length() > CHUNK_SIZE) {
                flushPiece(pieces, current);
            }
            current.append(s);
        }
        flushPiece(pieces, current);
        return pieces;
    }

    /** 把缓冲内容冲入切片列表 / Flush the buffer into the piece list. */
    private void flushPiece(List<String> pieces, StringBuilder current) {
        if (current.length() > 0) {
            pieces.add(current.toString().trim());
            current.setLength(0);
        }
    }

    /** 构造一个分块请求 / Build a chunk request. */
    private void addChunk(List<DocumentChunkRequest> chunks, String content, String fileName, int index) {
        DocumentChunkRequest req = new DocumentChunkRequest();
        req.setKind("document");
        req.setTitle(fileName + " #" + (index + 1));
        req.setContent(content);
        req.setChunkIndex(index);
        req.setSourceName(fileName);
        req.setTags(List.of("文档", fileName));
        chunks.add(req);
    }
}

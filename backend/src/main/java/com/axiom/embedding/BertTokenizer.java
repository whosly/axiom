package com.axiom.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BERT WordPiece 分词器 / BERT WordPiece tokenizer.
 *
 * <p>为本地 ONNX embedding 模型提供分词：加载 vocab.txt，实现 BasicTokenizer + WordPiece。
 * 中文按单字切分（BERT 原生行为），英文按 WordPiece 子词切分。
 * Provides tokenization for the local ONNX embedding model: loads vocab.txt and
 * implements BasicTokenizer + WordPiece. Chinese is split into characters (BERT native),
 * English into WordPiece subwords.
 *
 * <p>不依赖 Python 或外部服务，纯 Java 实现 / Pure Java, no Python or external service.
 */
@Slf4j
@Component
public class BertTokenizer {

    /** [CLS] token ID / [CLS] token ID. */
    public static final int CLS_ID = 101;
    /** [SEP] token ID / [SEP] token ID. */
    public static final int SEP_ID = 102;
    /** [PAD] token ID / [PAD] token ID. */
    public static final int PAD_ID = 0;

    /** 词表：token -> id / Vocabulary: token to id. */
    private Map<String, Integer> vocab = Map.of();
    /** 当前加载的词表路径 / Path of the currently loaded vocab. */
    private String loadedPath = "";

    /**
     * 加载词表（幂等）/ Load vocabulary (idempotent).
     *
     * @param vocabPath vocab.txt 路径 / Path to vocab.txt.
     */
    public synchronized void load(String vocabPath) {
        if (vocabPath.equals(loadedPath) && !vocab.isEmpty()) return;
        try (BufferedReader reader = Files.newBufferedReader(Path.of(vocabPath), StandardCharsets.UTF_8)) {
            Map<String, Integer> map = new HashMap<>();
            String line;
            int idx = 0;
            while ((line = reader.readLine()) != null) {
                String token = line.trim();
                if (!token.isEmpty()) map.put(token, idx);
                idx++;
            }
            this.vocab = map;
            this.loadedPath = vocabPath;
            log.info("[Tokenizer] loaded {} tokens from {}", map.size(), vocabPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load vocab: " + vocabPath, e);
        }
    }

    /** 词表是否已加载 / Whether the vocabulary is loaded. */
    public boolean isLoaded() { return !vocab.isEmpty(); }

    /**
     * 编码文本为 token ID 列表（含 [CLS]/[SEP]）/ Encode text into token IDs with [CLS]/[SEP].
     *
     * @param text     输入文本 / Input text.
     * @param maxLength 最大长度，超出截断 / Max length (truncate if longer).
     * @return token ID 列表 / Token ID list.
     */
    public List<Long> encode(String text, int maxLength) {
        if (!isLoaded()) throw new IllegalStateException("Tokenizer vocabulary not loaded");

        List<String> tokens = new ArrayList<>();
        tokens.add("[CLS]");
        tokens.addAll(wordpiece(text));
        tokens.add("[SEP]");

        if (tokens.size() > maxLength) tokens = new ArrayList<>(tokens.subList(0, maxLength));

        List<Long> ids = new ArrayList<>();
        for (String t : tokens) ids.add((long) vocab.getOrDefault(t, 100)); // 100 = [UNK]
        return ids;
    }

    /**
     * BasicTokenizer + WordPiece 切分 / BasicTokenizer plus WordPiece splitting.
     *
     * <p>对每个「词」：若整词在词表直接返回；否则从首字符开始，用 ## 前缀做最长匹配贪心切分。
     * / For each word: return directly if in vocab; otherwise greedily split with ## prefix
     * using longest-match-first.
     */
    private List<String> wordpiece(String text) {
        List<String> result = new ArrayList<>();
        for (String word : basicTokenize(text)) {
            if (vocab.containsKey(word)) {
                result.add(word);
                continue;
            }
            int start = 0;
            while (start < word.length()) {
                int end = word.length();
                String sub = null;
                while (start < end) {
                    String candidate = word.substring(start, end);
                    if (start > 0) candidate = "##" + candidate;
                    if (vocab.containsKey(candidate)) { sub = candidate; break; }
                    end--;
                }
                if (sub == null) {
                    sub = start == 0 ? "[UNK]" : "##[UNK]";
                    result.add("[UNK]");
                    start++;
                } else {
                    result.add(sub);
                    start = end;
                }
            }
        }
        return result;
    }

    /** 基础分词：按空白与非中文标点切分，中文单字成词 / Basic tokenization: split on whitespace
     * and non-CJK punctuation; each CJK character becomes a word. */
    private List<String> basicTokenize(String text) {
        List<String> words = new ArrayList<>();
        String lower = text.toLowerCase();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                if (cur.length() > 0) { words.add(cur.toString()); cur.setLength(0); }
                words.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                cur.append(c);
            } else {
                if (cur.length() > 0) { words.add(cur.toString()); cur.setLength(0); }
            }
        }
        if (cur.length() > 0) words.add(cur.toString());
        return words;
    }

    private boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }
}

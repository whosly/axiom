package com.dataagent.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量分词器 / Lightweight tokenizer.
 *
 * <p>倒排索引降级检索使用：中文按 bigram（相邻两字）切分，英文/数字按连续词切分。
 * 不依赖外部分词库，保证零配置可用。对「GMV」「华东」「销售额」这类查询都有较好召回。
 * Used for inverted-index fallback: Chinese is split into bigrams, English/digits into words.
 * No external tokenizer dependency, so it works with zero configuration.
 */
@Component
public class KnowledgeTokenizer {

    /** 英文/数字词 / ASCII word pattern. */
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]+");
    /** CJK 字符 / CJK character pattern. */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");

    /**
     * 对文本分词 / Tokenize text.
     *
     * @param text 输入文本 / Input text.
     * @return 去重后的分词列表 / Deduplicated token list.
     */
    public List<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return List.of();

        // 1. 英文/数字词（小写归一）/ ASCII words, lowercased.
        Matcher wordMatcher = WORD.matcher(text);
        while (wordMatcher.find()) {
            String w = wordMatcher.group().toLowerCase();
            if (w.length() >= 1) tokens.add(w);
        }

        // 2. 中文 bigram / Chinese bigrams.
        Matcher cjkMatcher = CJK.matcher(text);
        StringBuilder cjkRun = new StringBuilder();
        // 按连续中文段切分 bigram / Split into CJK runs then emit bigrams.
        int last = 0;
        while (cjkMatcher.find()) {
            if (cjkMatcher.start() != last) {
                emitBigrams(cjkRun, tokens);
                cjkRun.setLength(0);
            }
            cjkRun.append(cjkMatcher.group());
            last = cjkMatcher.end();
        }
        emitBigrams(cjkRun, tokens);

        return List.copyOf(tokens);
    }

    /** 对连续中文串生成 bigram / Emit bigrams for a CJK run. */
    private void emitBigrams(StringBuilder run, Set<String> tokens) {
        if (run.length() == 1) {
            tokens.add(run.toString());
        }
        for (int i = 0; i + 1 < run.length(); i++) {
            tokens.add(run.substring(i, i + 2));
        }
    }
}

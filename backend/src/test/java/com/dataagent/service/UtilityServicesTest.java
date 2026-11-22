package com.dataagent.service;

import com.dataagent.model.AnalysisPlan;
import com.dataagent.model.ColumnInfo;
import com.dataagent.model.Dataset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具类服务测试 / Tests for utility services.
 *
 * <p>覆盖中文分词、AES 加解密、规则降级计划等纯逻辑路径。
 * Covers pure-logic paths: Chinese tokenization, AES crypto and rule-based planning.
 */
class UtilityServicesTest {

    /** 分词器：英文小写归一 + 中文 bigram / Tokenizer: lowercase English + Chinese bigrams. */
    @Test
    @DisplayName("KnowledgeTokenizer 分词")
    void tokenizer() {
        KnowledgeTokenizer tokenizer = new KnowledgeTokenizer();
        assertThat(tokenizer.tokenize(null)).isEmpty();
        assertThat(tokenizer.tokenize("  ")).isEmpty();
        List<String> en = tokenizer.tokenize("GMV Revenue2024");
        assertThat(en).contains("gmv", "revenue2024");
        List<String> zh = tokenizer.tokenize("华东大区");
        assertThat(zh).contains("华东", "东大", "大区");
    }

    /** AES 加解密往返与空值处理 / AES encrypt-decrypt round-trip and empty handling. */
    @Test
    @DisplayName("CryptoService 加解密往返")
    void cryptoRoundTrip() {
        CryptoService crypto = new CryptoService();
        assertThat(crypto.encrypt(null)).isEmpty();
        assertThat(crypto.decrypt(null)).isEmpty();
        String plain = "jdbc:mysql://localhost:3306/db";
        String encrypted = crypto.encrypt(plain);
        assertThat(encrypted).isNotBlank().isNotEqualTo(plain);
        assertThat(crypto.decrypt(encrypted)).isEqualTo(plain);
        assertThat(crypto.decrypt("invalid-ciphertext")).isEmpty();
    }

    /** 规则计划：趋势问题映射 TREND 与时间列 / Rule planner maps trend question to TREND. */
    @Test
    @DisplayName("RuleBasedPlanner 趋势与概览")
    void rulePlanner() {
        RuleBasedPlanner planner = new RuleBasedPlanner();
        Dataset ds = new Dataset();
        ColumnInfo amount = new ColumnInfo();
        amount.setName("销售额"); amount.setOriginalName("销售额");
        amount.setType("numeric"); amount.setNullCount(0); amount.setSampleValues("");
        ColumnInfo dt = new ColumnInfo();
        dt.setName("日期"); dt.setOriginalName("日期");
        dt.setType("temporal"); dt.setNullCount(0); dt.setSampleValues("");
        ds.setColumns(List.of(amount, dt));

        AnalysisPlan trend = planner.plan(ds, "分析销售额的变化趋势");
        assertThat(trend.getIntent()).isEqualTo(com.dataagent.domain.AnalysisIntent.TREND);
        assertThat(trend.getTimeColumn()).isEqualTo("日期");

        AnalysisPlan overview = planner.plan(ds, "数据概览");
        assertThat(overview.getIntent()).isEqualTo(com.dataagent.domain.AnalysisIntent.SUMMARY);
    }
}

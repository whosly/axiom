package com.axiom.service;

import com.axiom.llm.LlmProvider;
import com.axiom.llm.LlmProviderFactory;
import com.axiom.llm.LlmProvider.ChatOptions;
import com.axiom.llm.LlmProvider.ChatMessage;
import com.axiom.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;

/** Orchestrates: schema+retrieval -> LLM plan -> deterministic engine -> LLM conclusion. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisOrchestrator {

    private final LlmProviderFactory llmFactory;
    private final AnalysisEngine engine;
    private final VectorService vectors;
    private final ObjectMapper mapper;
    private final RuleBasedPlanner rulePlanner;
    /** 知识库服务：在 plan/conclude 阶段注入业务知识 / Knowledge service: injects business knowledge into plan/conclude prompts. */
    private final KnowledgeService knowledgeService;
    /** 全局配置：读取热更新后的 temperature/maxTokens / Global config for hot-updated LLM params. */
    private final com.axiom.config.AppProperties props;

    /**
     * 构造对话选项，参数取自当前厂商的热更新配置 / Build chat options from the active
     * provider's hot-updated parameters.
     *
     * <p>此前 temperature/maxTokens 硬编码（0.1/1024、0.3/1024），配置面板的改动
     * 完全不生效。此处以配置面板值为准，仅在配置缺失时使用调用方的默认值。
     * / Hard-coded values meant runtime config changes had no effect; panel values
     * win now, with the caller's defaults only as a fallback.
     *
     * @param llm        当前厂商 / Active provider.
     * @param defaultTemp 兜底温度 / Fallback temperature.
     * @param defaultMaxTokens 兜底最大 token / Fallback max tokens.
     * @param jsonMode   是否强制 JSON 输出（plan 需要，conclude 是自然语言）/ Whether to force JSON output.
     * @return 对话选项 / Chat options.
     */
    private ChatOptions options(LlmProvider llm, double defaultTemp, int defaultMaxTokens, boolean jsonMode) {
        var cfg = props.getLlm().getProviders().get(llm.providerName());
        double temp = cfg != null ? cfg.getTemperature() : defaultTemp;
        int maxTokens = cfg != null && cfg.getMaxTokens() > 0 ? cfg.getMaxTokens() : defaultMaxTokens;
        // 注意：第 4 参是 jsonMode 而非 stream——流式与否由 chatStream/buildChatRequest 单独控制，
        // 传错会把 response_format 强加给不支持的厂商导致 plan 4xx 降级规则引擎。
        // / The 4th arg is jsonMode, NOT stream — streaming is controlled separately by
        // chatStream/buildChatRequest. Mispassing stream here forces response_format onto
        // providers that do not support it, degrading plan to the rule engine via 4xx.
        return new ChatOptions(llm.chatModel(), temp, maxTokens, jsonMode);
    }

    /** Whether the active LLM provider has a usable API key. */
    private boolean llmAvailable() {
        return llmFactory.isAvailable();
    }

    /** Step 1: natural language -> structured plan. */
    public AnalysisPlan plan(Dataset dataset, String question) {
        if (!llmAvailable()) {
            log.info("[Plan] no LLM API key configured, using rule-based planner");
            return rulePlanner.plan(dataset, question);
        }
        LlmProvider llm = llmFactory.current();
        List<VectorService.Hit> fieldHits = vectors.search(question, "field", 5);
        List<VectorService.Hit> historyHits = vectors.search(question, "history", 3);

        String schemaJson = dataset.getColumns().stream()
                .map(c -> String.format("{\"name\":\"%s\",\"type\":\"%s\",\"samples\":\"%s\"}",
                        c.getName(), c.getType(), c.getSampleValues() != null ? c.getSampleValues().replace("\"", "'") : ""))
                .reduce((a, b) -> a + "," + b).orElse("");

        StringBuilder sb = new StringBuilder();
        sb.append("你是数据分析计划生成器。根据用户问题和数据集字段，输出严格的 JSON 分析计划。\n");
        sb.append("字段列表: [").append(schemaJson).append("]\n");
        if (!fieldHits.isEmpty()) {
            sb.append("语义检索到的相关字段: ");
            sb.append(String.join("; ", fieldHits.stream().map(h -> h.getText()).toList())).append("\n");
        }
        if (!historyHits.isEmpty()) {
            sb.append("相似历史分析: ");
            sb.append(String.join("; ", historyHits.stream().map(h -> h.getText()).toList())).append("\n");
        }
        // 知识库上下文：注入指标口径、业务术语、分析模板，帮助模型正确映射业务概念到字段
        // Knowledge context: inject metric definitions, glossary and recipes so the model
        // can correctly map business concepts to physical columns.
        String kbContext = knowledgeService.retrieveContext(question, dataset.getId());
        if (!kbContext.isBlank()) sb.append(kbContext).append("\n");
        sb.append("""
            输出 JSON 格式（不要任何额外文字）:
            {"intent":"query|trend|anomaly|compare|summary",
             "metrics":["数值列名"],"aggregation":"sum|avg|count|min|max",
             "dimension":"分组列名","timeColumn":"时间列名",
             "timeGranularity":"day|week|month|quarter|year",
             "filters":{"列":{"op":"=|>|>=|<|<=|!=","value":"值"}},
             "sortBy":"列名","sortOrder":"asc|desc","limit":20,
             "chartType":"line|bar|pie|scatter|table","title":"中文标题","reasoning":"简短说明"}
            规则:
            - 问趋势/变化/走势 -> trend; 问异常/离群/突变 -> anomaly;
            - 问对比/各XX/排名 -> compare; 问概况/描述 -> summary; 其他 -> query
            - 字段名必须来自字段列表，不要编造
            """);
        sb.append("用户问题: ").append(question);

        String model = llm.chatModel();
        ChatOptions options = options(llm, 0.1, 1024, true);

        try {
            String response = llm.chat(List.of(ChatMessage.user(sb.toString())), options);
            String cleaned = stripCodeFence(response);
            AnalysisPlan plan = mapper.readValue(cleaned, AnalysisPlan.class);
            if (plan.getIntent() == null) plan.setIntent(com.axiom.domain.AnalysisIntent.QUERY);
            if (plan.getLimit() <= 0) plan.setLimit(20);
            if (plan.getChartType() == null) plan.setChartType(com.axiom.domain.ChartType.BAR);
            if (plan.getAggregation() == null) plan.setAggregation(com.axiom.domain.Aggregation.SUM);
            if (plan.getTimeGranularity() == null) plan.setTimeGranularity(com.axiom.domain.TimeGranularity.MONTH);
            if (plan.getSortOrder() == null) plan.setSortOrder(com.axiom.domain.SortOrder.DESC);
            // 对 LLM 输出做 schema 校验：幻觉字段剔除，关键字段全部无效时抛错降级规则引擎。
            // Validate against the real schema: drop hallucinated fields; degrade if nothing valid remains.
            validatePlan(dataset, plan);
            log.info("[Plan] intent={} metrics={} dim={}", plan.getIntent(), plan.getMetrics(), plan.getDimension());
            return plan;
        } catch (Exception e) {
            log.warn("[Plan] LLM call/parse failed, falling back to rules: {}", e.getMessage());
            return rulePlanner.plan(dataset, question);
        }
    }

    /**
     * 按 dataset 真实列校验并修正计划 / Validate the plan against actual dataset columns.
     *
     * <p>LLM 幻觉的列名（metrics/dimension/timeColumn/sortBy/filters key）如果放行，
     * 要么在 SQL 阶段报错，要么更糟——被静默忽略后返回错误答案。这里统一剔除无效项：
     * 剔除后仍缺少必要字段时抛 IllegalArgumentException，由调用方降级到规则引擎。
     * / Hallucinated column names either fail the SQL or worse get silently ignored and
     * return wrong answers. Invalid entries are dropped here; if required fields become
     * empty an IllegalArgumentException triggers the rule-based fallback.
     */
    private void validatePlan(Dataset dataset, AnalysisPlan plan) {
        Set<String> names = dataset.getColumns().stream().map(ColumnInfo::getName).collect(java.util.stream.Collectors.toSet());
        Set<String> numericNames = dataset.getColumns().stream()
                .filter(c -> "numeric".equals(c.getType())).map(ColumnInfo::getName).collect(java.util.stream.Collectors.toSet());

        // 过滤幻觉指标；COUNT 可以作用于任意列，其余聚合要求 numeric。
        // Drop hallucinated metrics; COUNT may target any column, other aggregates need numeric.
        List<String> metrics = plan.getMetrics() == null ? List.of() : plan.getMetrics().stream()
                .filter(m -> m != null && names.contains(m))
                .filter(m -> "count".equalsIgnoreCase(plan.getAggregation() == null ? "" : plan.getAggregation().getValue())
                        || numericNames.contains(m))
                .distinct().toList();
        plan.setMetrics(metrics);

        // dimension/timeColumn/sortBy：不存在或类型不符时置空（引擎会自动回退默认列）。
        // Null out invalid dimension/timeColumn/sortBy; the engine falls back to defaults.
        if (plan.getDimension() != null && (!names.contains(plan.getDimension())
                || "numeric".equals(typeOf(dataset, plan.getDimension())))) {
            plan.setDimension(null);
        }
        if (plan.getTimeColumn() != null && !"temporal".equals(typeOf(dataset, plan.getTimeColumn()))) {
            plan.setTimeColumn(null);
        }
        if (plan.getSortBy() != null && !names.contains(plan.getSortBy())) {
            plan.setSortBy(null);
        }
        // 过滤幻觉过滤条件 / Drop hallucinated filters.
        if (plan.getFilters() != null) {
            plan.getFilters().keySet().retainAll(names);
            if (plan.getFilters().isEmpty()) plan.setFilters(null);
        }

        // 关键字段全部无效 → 计划不可执行，抛错走规则引擎 / No usable metrics left → degrade.
        boolean needsMetrics = plan.getIntent() != com.axiom.domain.AnalysisIntent.SUMMARY;
        if (needsMetrics && metrics.isEmpty()) {
            throw new IllegalArgumentException("计划中的指标列均不存在于数据集");
        }
    }

    private String typeOf(Dataset dataset, String column) {
        return dataset.getColumns().stream()
                .filter(c -> c.getName().equals(column)).findFirst()
                .map(ColumnInfo::getType).orElse(null);
    }

    /** Step 2: execute the plan deterministically. */
    public AnalysisResult execute(Dataset dataset, AnalysisPlan plan) {
        return engine.execute(dataset, plan);
    }

    /** Step 3: turn result into a readable conclusion (optionally streamed). */
    public String conclude(Dataset dataset, String question, AnalysisPlan plan,
                           AnalysisResult result, Consumer<String> tokenConsumer) {
        return conclude(dataset, question, plan, result, tokenConsumer, null);
    }

    /**
     * 带取消信号的结论生成 / Conclusion generation with a cancellation signal.
     *
     * <p>cancelled 置位后 collector 抛 {@link java.util.concurrent.CancellationException}，
     * 异常穿透 chatStream 的读循环并触发其 try-with-resources 关闭 HTTP 响应——
     * 这是真正中断 LLM 服务端流式生成的唯一手段，仅靠「不消费 token」费用照烧。
     * Once cancelled, the collector throws {@link java.util.concurrent.CancellationException};
     * it unwinds chatStream's read loop and closes the HTTP response via try-with-resources —
     * the only way to actually stop server-side generation (merely ignoring tokens still
     * burns the full cost).
     */
    public String conclude(Dataset dataset, String question, AnalysisPlan plan,
                           AnalysisResult result, Consumer<String> tokenConsumer,
                           java.util.concurrent.atomic.AtomicBoolean cancelled) {
        if (!llmAvailable()) {
            String fallback = rulePlanner.conclude(dataset, question, plan, result);
            if (tokenConsumer != null) {
                // stream in small chunks for consistent UX
                for (int i = 0; i < fallback.length(); i += 8) {
                    tokenConsumer.accept(fallback.substring(i, Math.min(i + 8, fallback.length())));
                }
            }
            result.setConclusion(fallback);
            return fallback;
        }
        LlmProvider llm = llmFactory.current();
        StringBuilder context = new StringBuilder();
        context.append("数据集: ").append(dataset.getName()).append("\n");
        context.append("用户问题: ").append(question).append("\n");
        context.append("分析类型: ").append(plan.getIntent()).append("\n");
        context.append("分析SQL: ").append(result.getSql() != null ? result.getSql() : "(统计摘要)").append("\n");
        context.append("结果数据(JSON): ");
        try {
            context.append(mapper.writeValueAsString(result.getRows())).append("\n");
        } catch (Exception ignored) {}
        if (result.getInsights() != null && !result.getInsights().isEmpty()) {
            context.append("规则引擎洞察: ").append(String.join("; ", result.getInsights())).append("\n");
        }
        // 注入业务知识，让结论能结合指标口径和术语背景 / Inject business knowledge so conclusions
        // can reference metric definitions and glossary background.
        String kbContext = knowledgeService.retrieveContext(question, dataset.getId());
        if (!kbContext.isBlank()) context.append(kbContext).append("\n");
        context.append("""
            请基于以上真实计算结果，用中文写一段简明的分析结论。
            要求:
            - 直接回答用户问题，引用具体数字
            - 指出关键趋势/异常/差异及其业务含义
            - 给出1-2条可操作建议
            - 不要编造数据中不存在的数字
            """);

        String model = llm.chatModel();
        ChatOptions options = options(llm, 0.3, 1024, false);
        StringBuilder full = new StringBuilder();
        Consumer<String> collector = token -> {
            // 取消即中断：抛异常打断底层读循环并关闭流，而不是只在消费侧静默丢弃。
            // / Throwing on cancellation interrupts the underlying read loop and closes
            // the stream, instead of silently dropping tokens on the consumer side.
            if (cancelled != null && cancelled.get()) {
                throw new java.util.concurrent.CancellationException("client disconnected");
            }
            full.append(token);
            if (tokenConsumer != null) tokenConsumer.accept(token);
        };

        try {
            if (tokenConsumer != null) {
                llm.chatStream(List.of(ChatMessage.user(context.toString())), options, collector);
            } else {
                collector.accept(llm.chat(List.of(ChatMessage.user(context.toString())), options));
            }
        } catch (java.util.concurrent.CancellationException e) {
            // 客户端已断开：不做规则降级（结论不会被任何人消费），保留已生成的部分即可。
            // / Client is gone: no rule fallback (nobody will consume it); keep partial text.
            log.info("[Conclude] cancelled by client, {} chars generated", full.length());
        } catch (Exception e) {
            // plan 有降级路径，conclude 也必须有；否则流式场景会把半截 LLM 文本当最终结论。
            // 已推送部分 token 时明确标记中断，再补一段规则结论；一个 token 都没有则直接用规则结论。
            // / The conclude stage needs a fallback just like plan. If some tokens were already
            // streamed, mark the interruption and append the rule-based conclusion; if nothing
            // was streamed, use the rule conclusion directly.
            log.warn("[Conclude] LLM failed, falling back to rules: {}", e.getMessage());
            if (full.length() > 0) collector.accept("\n\n[模型连接中断，以下为规则引擎补充结论 / LLM interrupted, rule-based conclusion follows]\n");
            collector.accept(rulePlanner.conclude(dataset, question, plan, result));
        }
        String conclusion = full.toString();
        result.setConclusion(conclusion);
        return conclusion;
    }

    /** Index artifacts into Zvec for future retrieval. */
    public void memorize(Dataset dataset, String question, AnalysisPlan plan, AnalysisResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("问题: ").append(question).append("\n");
        sb.append("计划: ").append(plan.getIntent());
        if (plan.getMetrics() != null) sb.append(" 指标=").append(String.join(",", plan.getMetrics()));
        if (plan.getDimension() != null) sb.append(" 维度=").append(plan.getDimension());
        sb.append("\n结论: ").append(result.getConclusion() != null ? result.getConclusion() : "");

        String historyText = sb.toString();
        String conclusionText = dataset.getName() + ": "
                + (result.getConclusion() != null ? result.getConclusion() : "");

        // 向量主键用内容哈希派生：Zvec upsert 按 PK 覆盖，同内容反复分析只保留一份，
        // 不再随分析次数无限膨胀。两条向量合并为一次批量写入，只付一次刷盘代价。
        // / Deterministic PKs derived from content: Zvec upsert overwrites by PK, so
        // repeated identical analyses converge to one vector. Both go in one batched
        // write to pay a single flush cost.
        vectors.upsertAll(List.of(
                new VectorService.SemanticItem("history-" + shortHash(historyText), "history", dataset.getId(), historyText),
                new VectorService.SemanticItem("concl-" + shortHash(conclusionText), "conclusion", dataset.getId(), conclusionText)));

        // 同时沉淀为知识库的 case 条目，供跨会话的 few-shot 检索使用
        // Also distill into the knowledge base as a case entry for cross-session few-shot retrieval.
        knowledgeService.addFromAnalysis(dataset, question, plan, result);
    }

    /** SHA-256 前 32 位十六进制，用作向量主键 / 32-hex prefix of SHA-256, used as vector PK. */
    private String shortHash(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 在所有 JDK 中必然存在，理论不可达 / SHA-256 always exists in the JDK.
            return Integer.toHexString(text.hashCode());
        }
    }

    /** Index all dataset columns with their semantic descriptions. */
    public void indexDataset(Dataset dataset) {
        if (dataset.getColumns() == null) return;
        // 全部字段一次批量写入：宽表上传不再按列数放大刷盘次数。
        // / One batched write for all columns: wide-table upload no longer multiplies flushes.
        List<VectorService.SemanticItem> items = new ArrayList<>(dataset.getColumns().size());
        for (ColumnInfo col : dataset.getColumns()) {
            String text = String.format("数据集[%s]字段[%s] 类型[%s] 样例[%s]",
                    dataset.getName(), col.getName(), col.getType(), col.getSampleValues());
            items.add(new VectorService.SemanticItem(dataset.getId() + "_" + col.getName(), "field", dataset.getId(), text));
        }
        vectors.upsertAll(items);
    }

    private String stripCodeFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) t = t.substring(start, end + 1);
        return t;
    }
}

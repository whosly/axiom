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
        ChatOptions options = new ChatOptions(model, 0.1, 1024, true);

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
            log.info("[Plan] intent={} metrics={} dim={}", plan.getIntent(), plan.getMetrics(), plan.getDimension());
            return plan;
        } catch (Exception e) {
            log.warn("[Plan] LLM call/parse failed, falling back to rules: {}", e.getMessage());
            return rulePlanner.plan(dataset, question);
        }
    }

    /** Step 2: execute the plan deterministically. */
    public AnalysisResult execute(Dataset dataset, AnalysisPlan plan) {
        return engine.execute(dataset, plan);
    }

    /** Step 3: turn result into a readable conclusion (optionally streamed). */
    public String conclude(Dataset dataset, String question, AnalysisPlan plan,
                           AnalysisResult result, Consumer<String> tokenConsumer) {
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
        ChatOptions options = new ChatOptions(model, 0.3, 1024, false);
        StringBuilder full = new StringBuilder();
        Consumer<String> collector = token -> { full.append(token); if (tokenConsumer != null) tokenConsumer.accept(token); };

        if (tokenConsumer != null) {
            llm.chatStream(List.of(ChatMessage.user(context.toString())), options, collector);
        } else {
            collector.accept(llm.chat(List.of(ChatMessage.user(context.toString())), options));
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

        vectors.upsert(UUID.randomUUID().toString(), "history", dataset.getId(), sb.toString());
        vectors.upsert(UUID.randomUUID().toString(), "conclusion", dataset.getId(),
                dataset.getName() + ": " + (result.getConclusion() != null ? result.getConclusion() : ""));

        // 同时沉淀为知识库的 case 条目，供跨会话的 few-shot 检索使用
        // Also distill into the knowledge base as a case entry for cross-session few-shot retrieval.
        knowledgeService.addFromAnalysis(dataset, question, plan, result);
    }

    /** Index all dataset columns with their semantic descriptions. */
    public void indexDataset(Dataset dataset) {
        if (dataset.getColumns() == null) return;
        for (ColumnInfo col : dataset.getColumns()) {
            String text = String.format("数据集[%s]字段[%s] 类型[%s] 样例[%s]",
                    dataset.getName(), col.getName(), col.getType(), col.getSampleValues());
            vectors.upsert(dataset.getId() + "_" + col.getName(), "field", dataset.getId(), text);
        }
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

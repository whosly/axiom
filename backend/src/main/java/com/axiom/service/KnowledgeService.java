package com.axiom.service;

import com.axiom.model.AnalysisPlan;
import com.axiom.model.AnalysisResult;
import com.axiom.model.Dataset;
import com.axiom.model.DocumentChunkRequest;
import com.axiom.model.KnowledgeDetail;
import com.axiom.model.KnowledgeEntry;
import com.axiom.model.KnowledgeRequest;
import com.axiom.model.KnowledgeSource;
import com.axiom.repository.KnowledgeRepository;
import com.axiom.repository.KnowledgeSourceRepository;
import com.axiom.repository.MetadataRepository;
import com.axiom.config.AppProperties;
import com.axiom.domain.KnowledgeChangedEvent;
import com.axiom.rerank.RerankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 知识库统一服务 / Unified knowledge base service.
 *
 * <p>作为知识库的门面（Facade），编排三类知识来源：
 * Acts as the facade of the knowledge base, orchestrating three knowledge sources:
 * <ol>
 *   <li>手动录入：指标口径、术语、模板 / Manual entry: metrics, glossary, recipes</li>
 *   <li>分析历史：从过往分析中沉淀 / Analysis history: distilled from past analyses</li>
 *   <li>（后续）文档：PDF/Word/MD 分块 / Documents: PDF/Word/MD chunks (future)</li>
 * </ol>
 *
 * <p>所有来源都归一化为 KnowledgeEntry，共享 KnowledgeRepository（元数据）和
 * KnowledgeVectorStore（向量）两层。/ All sources are normalized to KnowledgeEntry and
 * share the metadata and vector layers.
 */
@Slf4j
@Service
public class KnowledgeService {

    private final KnowledgeRepository repository;
    private final KnowledgeVectorStore vectorStore;
    private final MetadataRepository metadataRepository;
    private final KnowledgeTokenizer tokenizer;
    private final AppProperties props;
    private final RerankService rerankService;
    /** 领域事件发布器，用于通知图谱自动同步 / Domain event publisher for graph auto-sync. */
    private final ApplicationEventPublisher eventPublisher;
    /** 知识来源元数据仓储 / Knowledge source metadata repository. */
    private final KnowledgeSourceRepository sourceRepository;

    /** reindex 与用户写入的互斥锁 / Mutual exclusion between reindex and user writes. */
    private final Object reindexLock = new Object();

    public KnowledgeService(KnowledgeRepository repository, KnowledgeVectorStore vectorStore,
                            MetadataRepository metadataRepository, KnowledgeTokenizer tokenizer,
                            AppProperties props, RerankService rerankService,
                            ApplicationEventPublisher eventPublisher,
                            KnowledgeSourceRepository sourceRepository) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.metadataRepository = metadataRepository;
        this.tokenizer = tokenizer;
        this.props = props;
        this.rerankService = rerankService;
        this.eventPublisher = eventPublisher;
        this.sourceRepository = sourceRepository;
    }

    /** 发布知识变更事件（图谱增量同步）/ Publish a knowledge-change event for incremental graph sync. */
    private void publishChange(KnowledgeChangedEvent.ChangeType type, String entryId) {
        publishChange(type, entryId == null ? java.util.List.of() : java.util.List.of(entryId));
    }

    /** 发布批量知识变更事件 / Publish a batch knowledge-change event. */
    private void publishChange(KnowledgeChangedEvent.ChangeType type, java.util.List<String> entryIds) {
        if (entryIds == null || entryIds.isEmpty()) return;
        eventPublisher.publishEvent(new KnowledgeChangedEvent(this, type, entryIds));
    }

    /**
     * 登记/更新知识来源 / Register or update a knowledge source.
     *
     * @param sourceId 来源 ID / Source ID.
     * @param name     名称 / Name.
     * @param type     类型 / Type.
     * @param count    条目数 / Entry count.
     */
    private void registerSource(String sourceId, String name, String type, int count) {
        if (sourceId == null || sourceId.isBlank()) return;
        KnowledgeSource source = sourceRepository.findById(sourceId);
        LocalDateTime now = LocalDateTime.now();
        if (source == null) {
            source = new KnowledgeSource();
            source.setId(sourceId);
            source.setCreatedAt(now);
        }
        source.setName(name);
        source.setType(type);
        source.setEntryCount(count);
        source.setStatus("active");
        source.setUpdatedAt(now);
        sourceRepository.save(source);
    }

    /** 列出全部知识来源 / List all knowledge sources. */
    public List<KnowledgeSource> listSources() { return sourceRepository.listAll(); }

    /** 删除知识来源（级联删除其知识）/ Delete a knowledge source and its entries. */
    public int deleteSource(String sourceId) {
        int count = deleteBySource(sourceId);
        sourceRepository.delete(sourceId);
        return count;
    }

    /**
     * 手动录入一条知识 / Add a knowledge entry manually.
     *
     * <p>内容哈希去重：相同内容重复录入时直接返回已有条目，保证幂等。
     * Content-hash dedup: re-adding identical content returns the existing entry (idempotent).
     *
     * @param req 录入请求 / The creation request.
     * @return 已入库的知识条目 / The persisted knowledge entry.
     */
    public KnowledgeEntry add(KnowledgeRequest req) {
        String content = req.getContent() == null ? "" : req.getContent();
        String hash = sha256((req.getTitle() == null ? "" : req.getTitle()) + "|" + content);

        // 公式语法前置校验：坏公式在录入期拒绝，而不是等到分析执行才报 SQL 错。
        // / Validate formula syntax at ingestion so broken formulas never reach analysis.
        validateFormula(req.getFormula());

        KnowledgeEntry existing = repository.findByHash(hash);
        if (existing != null) {
            log.info("[Knowledge] duplicate content, skip: {}", req.getTitle());
            // 返回同哈希的已有（未删除）条目 / Return the existing live entry with the same hash.
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        KnowledgeEntry entry = KnowledgeEntry.builder()
                .id(UUID.randomUUID().toString())
                .kind(req.getKind() == null ? "glossary" : req.getKind())
                .title(req.getTitle())
                .content(content)
                .formula(req.getFormula())
                .fields(req.getFields())
                .tags(req.getTags())
                .sourceType("manual")
                .sourceId("manual-" + UUID.randomUUID())
                .datasetId(req.getDatasetId())
                .textHash(hash)
                .status("pending")
                .createdAt(now)
                .updatedAt(now)
                .build();

        persistWithVector(entry);
        registerSource(entry.getSourceId(), req.getTitle() == null ? "手动录入" : req.getTitle(), "manual", 1);
        publishChange(KnowledgeChangedEvent.ChangeType.ADD, entry.getId());
        log.info("[Knowledge] added entry {} kind={}", entry.getId(), entry.getKind());
        return entry;
    }

    /**
     * 双写持久化：元数据先落地（pending），再写向量，按结果更新状态。
     * Dual-write persistence: persist metadata first (pending), then vector, then update status.
     *
     * <p>状态机 / State machine:
     * <pre>
     *   pending --向量成功--> ready
     *   pending --向量失败--> vector_failed --reindex--> pending --> ready
     * </pre>
     * 这样即使 Embedding 服务不可用，元数据也不丢，后续可重建。
     * / Even if the embedding service is down, metadata is kept and can be reindexed later.
     *
     * @param entry 知识条目 / Knowledge entry.
     * @return 向量是否写入成功 / Whether the vector write succeeded.
     */
    private boolean persistWithVector(KnowledgeEntry entry) {
        // 1. 元数据先写，状态 pending / Persist metadata first with pending status.
        repository.save(entry);
        // 1b. 构建倒排索引（供降级检索）/ Build inverted index (for fallback search).
        java.util.List<String> tokens = tokenizer.tokenize(entry.getTitle() + " " + entry.getContent());
        repository.indexTerms(entry.getId(), tokens);
        // 2. 写向量 / Write vector.
        // 空内容永远无法向量化：直接进终态 skipped，避免被 pending 查询无限重试。
        // / Blank content can never be vectorized: go to terminal 'skipped' instead of
        // being retried forever by the pending query.
        boolean blank = entry.getContent() == null || entry.getContent().isBlank();
        boolean ok = !blank && vectorStore.upsertWithResult(entry);
        // 3. 按结果更新状态 / Update status by result.
        String finalStatus = ok ? "ready" : (blank ? "skipped" : "vector_failed");
        repository.updateStatus(entry.getId(), finalStatus);
        entry.setStatus(finalStatus);
        return ok;
    }

    /**
     * 批量入库知识条目 / Batch-add knowledge entries.
     *
     * <p>供文档解析等来源使用：相同 sourceId 的多个块属于同一文档，
     * 便于后续按来源级联删除。只发布一次 BATCH 事件，避免每条触发图谱重建。
     * / Used by sources like document parsing: multiple chunks sharing a sourceId belong
     * to one document, enabling cascade deletion. Publishes a single BATCH event to avoid
     * rebuilding the graph per entry.
     *
     * @param requests 条目请求列表 / List of entry requests.
     * @param sourceId 来源 ID（如 document-<file>）/ Source ID (e.g. document-<file>).
     * @return 成功入库条数 / Number of entries added.
     */
    public int addBatch(List<KnowledgeRequest> requests, String sourceId) {
        java.util.List<KnowledgeEntry> staged = new java.util.ArrayList<>();
        java.util.List<String> addedIds = new java.util.ArrayList<>();
        for (KnowledgeRequest req : requests) {
            String content = req.getContent() == null ? "" : req.getContent();
            String hash = sha256((req.getTitle() == null ? "" : req.getTitle()) + "|" + content);
            if (repository.existsByHash(hash)) continue;
            validateFormula(req.getFormula());

            LocalDateTime now = LocalDateTime.now();
            KnowledgeEntry entry = KnowledgeEntry.builder()
                    .id(UUID.randomUUID().toString())
                    .kind(req.getKind() == null ? "document" : req.getKind())
                    .title(req.getTitle())
                    .content(content)
                    .formula(req.getFormula())
                    .fields(req.getFields())
                    .tags(req.getTags())
                    .sourceType(req.getKind() == null ? "document" : req.getKind())
                    .sourceId(sourceId)
                    .datasetId(req.getDatasetId())
                    .chunkIndex(req instanceof DocumentChunkRequest dcr ? dcr.getChunkIndex() : 0)
                    .textHash(hash)
                    .status("pending")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            staged.add(entry);
        }

        // 批量路径：元数据逐条落库（保留单条失败粒度），向量合并为一次 upsert + 一次 flush。
        // 逐条 persistWithVector 会对每个块各刷一次盘，百块文档导入被刷盘开销支配。
        // / Batch path: metadata persisted per entry (keeps per-entry failure granularity),
        // vectors merged into a single upsert + flush. Per-entry persistWithVector flushes
        // once per chunk, which dominated bulk document import cost.
        for (KnowledgeEntry entry : staged) {
            repository.save(entry);
            repository.indexTerms(entry.getId(), tokenizer.tokenize(entry.getTitle() + " " + entry.getContent()));
        }
        java.util.List<Boolean> vectorResults = vectorStore.upsertBatch(staged);
        for (int i = 0; i < staged.size(); i++) {
            KnowledgeEntry entry = staged.get(i);
            // 空内容条目直接进 skipped 终态（与单条路径一致），否则会进 vector_failed
            // 被 reindex 无限重试——空内容永远写不出向量。/ Blank entries go straight
            // to the terminal "skipped" state (same as the single path) instead of
            // being retried forever by reindex.
            boolean blank = entry.getContent() == null || entry.getContent().isBlank();
            boolean ok = i < vectorResults.size() && vectorResults.get(i);
            // 按写入结果更新双写状态机 / Update the dual-write status machine by result.
            String finalStatus = blank ? "skipped" : ok ? "ready" : "vector_failed";
            repository.updateStatus(entry.getId(), finalStatus);
            entry.setStatus(finalStatus);
            addedIds.add(entry.getId());
        }
        int added = staged.size();

        if (added > 0) {
            String srcName = requests.isEmpty() ? sourceId : (requests.get(0) instanceof DocumentChunkRequest dcr
                    ? dcr.getSourceName() : sourceId);
            String srcType = requests.isEmpty() ? "import" : (requests.get(0).getKind() == null
                    ? "document" : requests.get(0).getKind());
            registerSource(sourceId, srcName, srcType, added);
            // 图谱按实际新增 ID 增量同步 / Sync graph incrementally by actual added IDs.
            publishChange(KnowledgeChangedEvent.ChangeType.BATCH, addedIds);
        }
        log.info("[Knowledge] batch added {} entries from {}", added, sourceId);
        return added;
    }

    /**
     * 从一次分析沉淀知识 / Distill knowledge from a completed analysis.
     *
     * <p>把「问题 + 计划 + 结论」作为一条 case 入库，供后续相似问题 few-shot 检索。
     * Stores "question + plan + conclusion" as a case for few-shot retrieval of similar questions.
     *
     * @param dataset   数据集 / The dataset.
     * @param question  用户问题 / User question.
     * @param plan      分析计划 / Analysis plan.
     * @param result    分析结果 / Analysis result.
     */
    public void addFromAnalysis(Dataset dataset, String question, AnalysisPlan plan, AnalysisResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("问题: ").append(question).append("\n");
        sb.append("意图: ").append(plan.getIntent()).append("\n");
        if (plan.getMetrics() != null) sb.append("指标: ").append(String.join(",", plan.getMetrics())).append("\n");
        if (plan.getDimension() != null) sb.append("维度: ").append(plan.getDimension()).append("\n");
        sb.append("结论: ").append(result.getConclusion() == null ? "" : result.getConclusion());

        // 幂等去重：相同 问答+结论 直接复用已有条目，避免同一问题反复沉淀出近似 case。
        // 与 addBatch 的去重语义对齐。/ Idempotent: identical question+conclusion reuses the
        // existing case entry, aligned with the addBatch dedup semantics.
        String textHash = sha256(sb.toString());
        KnowledgeEntry existing = repository.findByHash(textHash);
        if (existing != null) {
            log.debug("[Knowledge] analysis case already exists (hash match), skipping: {}", existing.getId());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        KnowledgeEntry entry = KnowledgeEntry.builder()
                .id(UUID.randomUUID().toString())
                .kind("case")
                .title(question.length() > 60 ? question.substring(0, 60) + "..." : question)
                .content(sb.toString())
                .sourceType("analysis")
                .sourceId("analysis-" + dataset.getId())
                .datasetId(dataset.getId())
                .tags(List.of(plan.getIntent().getValue()))
                .textHash(sha256(sb.toString()))
                .status("pending")
                .createdAt(now)
                .updatedAt(now)
                .build();

        persistWithVector(entry);
        registerSource(entry.getSourceId(), dataset.getName() + " 分析沉淀", "analysis", 1);
        publishChange(KnowledgeChangedEvent.ChangeType.ADD, entry.getId());
        log.info("[Knowledge] distilled case from analysis: {}", entry.getId());
    }

    /**
     * 混合检索知识 / Hybrid-search knowledge.
     *
     * @param query     查询文本 / Query text.
     * @param kind      类型过滤（可空）/ Kind filter (nullable).
     * @param datasetId 数据集过滤（可空）/ Dataset filter (nullable).
     * @param topK      返回条数 / Result count.
     * @return 命中列表 / List of hits.
     */
    public List<KnowledgeVectorStore.Hit> search(String query, String kind, String datasetId, int topK) {
        List<KnowledgeVectorStore.Hit> candidates = List.of();
        if (vectorStore.isReady()) {
            // 先召回更多候选，供 rerank 精排 / Recall more candidates for rerank to refine.
            int candidateSize = Math.max(topK, props.getRerank().getCandidateSize());
            candidates = vectorStore.hybridSearch(query, kind, datasetId, candidateSize);
        }
        if (candidates.isEmpty()) {
            // 向量检索无结果时降级为倒排索引关键词匹配（保持同一 datasetId 边界）
            // Fall back to inverted-index keyword search under the same dataset boundary.
            log.debug("[Knowledge] vector search returned no hits, trying keyword fallback");
            candidates = keywordFallback(query, kind, datasetId, Math.max(topK, props.getRerank().getCandidateSize()));
        }
        // 重排精排 / Rerank refinement.
        return rerankService.rerank(query, candidates, topK);
    }

    /**
     * 检索供 LLM 使用的知识上下文 / Retrieve knowledge context for LLM prompts.
     *
     * <p>返回格式化文本，直接拼入 Plan/Conclude 的 prompt。
     * Returns formatted text to be appended into Plan/Conclude prompts.
     *
     * @param question  用户问题 / User question.
     * @param datasetId 数据集 ID / Dataset ID.
     * @return 知识上下文文本（可能为空）/ Knowledge context text (possibly empty).
     */
    public String retrieveContext(String question, String datasetId) {
        List<KnowledgeVectorStore.Hit> hits = search(question, null, datasetId, 5);
        if (hits.isEmpty()) return "";

        // 相关性阈值：低于阈值的命中视为噪音（如分数全 0 的无关条目），不注入 prompt。
        // 把不可信内容标为"权威知识"比不注入更危险。/ Noise floor: hits below the threshold
        // (e.g. all-zero scores) are excluded — injecting untrusted content as "authoritative"
        // knowledge is worse than injecting nothing.
        float minScore = 0.05f;
        List<KnowledgeVectorStore.Hit> relevant = hits.stream()
                .filter(h -> h.getScore() >= minScore).toList();
        if (relevant.isEmpty()) return "";

        int budget = props.getRerank().getContextCharBudget();
        StringBuilder sb = new StringBuilder();
        sb.append("以下是知识库中与问题相关的知识（请优先参考，不要编造）：\n");
        int used = sb.length();
        Set<String> seen = new java.util.LinkedHashSet<>();
        int index = 0;

        for (KnowledgeVectorStore.Hit h : relevant) {
            // 去重：相同标题+内容只保留一条 / Deduplicate by title+content.
            String key = (h.getTitle() == null ? "" : h.getTitle()) + "|"
                    + (h.getContent() == null ? "" : h.getContent());
            if (!seen.add(key)) continue;

            String line = "[" + (++index) + "] [" + h.getKind() + "] "
                    + (h.getTitle() == null ? "" : h.getTitle()) + ": "
                    + (h.getContent() == null ? "" : h.getContent()) + "\n";
            // 字符预算：跳过超长条目继续尝试更短的相关条目，而不是丢弃全部后续命中。
            // Skip the oversized entry but keep scanning shorter, more relevant ones.
            if (used + line.length() > budget) continue;
            sb.append(line);
            used += line.length();
        }
        return sb.toString();
    }

    /**
     * 元数据关键词降级检索 / Keyword fallback search on metadata.
     *
     * <p>向量库不可用时的兜底：简单的包含匹配，保证服务不中断。
     * Fallback when vector store is down: simple substring match to keep the service alive.
     */
    private List<KnowledgeVectorStore.Hit> keywordFallback(String query, String kind, String datasetId, int topK) {
        // 用倒排索引检索，命中数即相关性分数，避免全表扫描 / Search via inverted index;
        // hit count acts as relevance score, avoiding full-table scans.
        List<String> terms = tokenizer.tokenize(query);
        List<Map<String, Object>> ranked = repository.searchByTerms(terms, datasetId, topK);
        List<KnowledgeVectorStore.Hit> hits = new ArrayList<>();
        for (Map<String, Object> row : ranked) {
            String entryId = String.valueOf(mapValue(row, "entry_id"));
            KnowledgeEntry e = repository.findById(entryId);
            if (e == null) continue;
            if (kind != null && !kind.isBlank() && !kind.equals(e.getKind())) continue;
            Object hitsObj = mapValue(row, "hits");
            long hitsCount = hitsObj instanceof Number n ? n.longValue() : 0;
            // 分数归一化：命中越多分越高，上限 1.0 / Normalize score by hit count, capped at 1.0.
            float score = (float) Math.min(1.0, hitsCount / (double) Math.max(terms.size(), 1));
            hits.add(new KnowledgeVectorStore.Hit(e.getId(), e.getKind(), e.getTitle(),
                    e.getContent(), e.getSourceId(), score));
        }
        // 兼容旧数据：倒排索引未覆盖时，退回子串匹配 / Backward compatibility: fall back to
        // substring match when inverted index has no data (e.g. legacy entries).
        if (hits.isEmpty()) {
            List<KnowledgeEntry> entries = kind == null ? repository.listAll() : repository.listByKind(kind);
            for (KnowledgeEntry e : entries) {
                // 子串兜底同样遵守 datasetId 边界 / Substring fallback respects the same boundary.
                if (datasetId != null && !datasetId.isBlank()
                        && e.getDatasetId() != null && !datasetId.equals(e.getDatasetId())) continue;
                String hay = (e.getTitle() == null ? "" : e.getTitle()) + " " + (e.getContent() == null ? "" : e.getContent());
                if (hay.contains(query)) {
                    hits.add(new KnowledgeVectorStore.Hit(e.getId(), e.getKind(), e.getTitle(),
                            e.getContent(), e.getSourceId(), 1.0f));
                }
                if (hits.size() >= topK) break;
            }
        }
        return hits;
    }

    /**
     * 获取知识条目完整详情 / Get full detail of a knowledge entry.
     *
     * <p>聚合四层信息：/ Aggregates four layers of information:
     * <ol>
     *   <li>元数据（H2 中的结构化字段）/ Metadata (structured fields in H2)</li>
     *   <li>向量状态（Zvec 中是否真正写入）/ Vector status (whether actually written to Zvec)</li>
     *   <li>溯源（来源数据集、来源类型）/ Traceability (source dataset and type)</li>
     *   <li>处理链路（从原始输入到入库的步骤）/ Processing lineage (steps from raw input to storage)</li>
     * </ol>
     *
     * @param id 知识条目 ID / Knowledge entry ID.
     * @return 详情对象，不存在时返回 null / Detail object, null if not found.
     */
    public KnowledgeDetail getDetail(String id) {
        KnowledgeEntry e = repository.findById(id);
        if (e == null) return null;

        KnowledgeDetail detail = new KnowledgeDetail();
        detail.setEntry(e);
        detail.setVector(vectorStore.vectorStatus(id));

        // 溯源：根据来源类型生成可读描述 / Traceability: readable description by source type.
        String sourceType = e.getSourceType() == null ? "" : e.getSourceType();
        String sourceLabel = switch (sourceType) {
            case "manual" -> "手动录入 / Manual entry";
            case "analysis" -> "分析沉淀 / Distilled from analysis";
            case "document" -> "文档解析 / Document parsing";
            case "import" -> "批量导入 / Bulk import";
            default -> sourceType;
        };
        detail.setSourceLabel(sourceLabel);

        // 关联数据集名称 / Associated dataset name.
        if (e.getDatasetId() != null && !e.getDatasetId().isBlank()) {
            Dataset ds = metadataRepository.getDataset(e.getDatasetId());
            detail.setDatasetName(ds == null ? e.getDatasetId() : ds.getName());
        } else {
            detail.setDatasetName("全局知识 / Global");
        }

        detail.setLineage(buildLineage(e));
        detail.setStorage(Map.of(
                "metadata", "H2/SQLite: knowledge_entries 表 / table",
                "vector", "Zvec: " + props.getZvec().getKnowledgeCollection() + " collection",
                "vectorId", e.getId()));
        return detail;
    }

    /**
     * 构造处理链路 / Build the processing lineage.
     *
     * <p>链路描述这条知识经历的每一步，让管理员能理解数据是如何被加工的。
     * / The lineage describes every step the knowledge went through, so admins can
     * understand how the data was processed.
     */
    private List<Map<String, String>> buildLineage(KnowledgeEntry e) {
        List<Map<String, String>> steps = new ArrayList<>();
        String sourceType = e.getSourceType() == null ? "" : e.getSourceType();

        // 步骤 1：来源 / Step 1: source.
        steps.add(step("1", "来源采集 / Source",
                switch (sourceType) {
                    case "manual" -> "用户在管理页手动输入 / User typed in the management page";
                    case "analysis" -> "对话分析完成后自动沉淀 / Auto-distilled after a chat analysis";
                    case "document" -> "文档解析分块 / Document parsed and chunked";
                    default -> "未知来源 / Unknown source";
                }));

        // 步骤 2：归一化 / Step 2: normalization.
        steps.add(step("2", "归一化 / Normalize",
                "映射为统一 KnowledgeEntry 结构，计算 SHA-256 去重 / Mapped to KnowledgeEntry, SHA-256 dedup computed"));

        // 步骤 3：元数据入库 / Step 3: metadata persistence.
        steps.add(step("3", "元数据入库 / Metadata",
                "写入 knowledge_entries 表（状态=" + e.getStatus() + "）/ Written to knowledge_entries table"));

        // 步骤 4：向量化 / Step 4: embedding.
        KnowledgeVectorStore.VectorStatus vs = vectorStore.vectorStatus(e.getId());
        steps.add(step("4", "向量化 / Embedding",
                vs.isPresent()
                        ? "已生成 " + vs.getDimension() + " 维向量并写入 Zvec / Vector embedded and stored in Zvec"
                        : "向量缺失（可能未配置 Embedding API）/ Vector missing (embedding API may be unconfigured)"));

        // 步骤 5：可检索 / Step 5: retrievable.
        steps.add(step("5", "可检索 / Retrieval",
                "混合检索：向量 + 全文 + RRF 融合 / Hybrid: vector + full-text + RRF fusion"));
        return steps;
    }

    /** 构造一个链路步骤 / Build a lineage step. */
    private Map<String, String> step(String num, String stage, String detail) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("step", num);
        m.put("stage", stage);
        m.put("detail", detail);
        return m;
    }

    /** 列出全部知识条目 / List all entries. */
    public List<KnowledgeEntry> listAll() { return repository.listAll(); }

    /** 按类型列出 / List by kind. */
    public List<KnowledgeEntry> listByKind(String kind) { return repository.listByKind(kind); }

    /** 类型统计 / Statistics grouped by kind. */
    public List<Map<String, Object>> stats() { return repository.stats(); }

    /** 状态统计（双写一致性监控）/ Status statistics for dual-write monitoring. */
    public List<Map<String, Object>> statusStats() { return repository.statusStats(); }

    /**
     * 重建向量索引（双写一致性修复）/ Rebuild vector index (dual-write consistency repair).
     *
     * <p>扫描所有 pending/vector_failed 的条目重新向量化；也可 force=true 强制重建全部。
     * / Scans all pending/vector_failed entries and re-embeds them; force=true rebuilds everything.
     *
     * @param force 是否强制重建全部 / Whether to force-rebuild all entries.
     * @return 重建结果统计 / Rebuild result statistics.
     */
    public Map<String, Object> reindex(boolean force) {
        // 互斥锁：启动同步线程、管理接口、用户写入三方并发时，快照里"读取后已删除"的
        // 条目会被重新写入向量成为幽灵知识；两个 reindex 也会互相覆盖状态。
        // / Mutual exclusion: without it, entries deleted mid-scan get re-vectorized as
        // ghosts, and two concurrent reindexes overwrite each other's status writes.
        synchronized (reindexLock) {
            List<KnowledgeEntry> targets = force ? repository.listAll() : repository.listPendingVector();
            int success = 0, failed = 0;
            // 先集中做元数据侧工作（倒排重建 + 标 pending），再一次性批量写向量：
            // 逐条 upsert+flush 会让百条重建被刷盘开销支配，与批量导入路径行为一致化。
            // / Do metadata-side work first (inverted index + pending flag), then one batched
            // vector write — per-entry upsert+flush would let flush cost dominate a hundred-entry
            // rebuild, consistent with the batch-import path.
            List<KnowledgeEntry> valid = new ArrayList<>(targets.size());
            for (KnowledgeEntry e : targets) {
                // 跳过扫描期间被软删的条目，避免幽灵向量 / Skip entries soft-deleted mid-scan.
                KnowledgeEntry current = repository.findById(e.getId());
                if (current == null) continue;
                // 空内容永远无法向量化：标为终态 skipped，避免被 pending 查询无限捞起重试。
                // / Blank content can never be vectorized: mark terminal 'skipped' instead of
                // letting the pending query retry it forever.
                if (current.getContent() == null || current.getContent().isBlank()) {
                    repository.updateStatus(e.getId(), "skipped");
                    continue;
                }
                // 重建倒排索引 / Rebuild inverted index.
                repository.indexTerms(e.getId(), tokenizer.tokenize(current.getTitle() + " " + current.getContent()));
                // 先标记 pending / Mark as pending first.
                repository.updateStatus(e.getId(), "pending");
                valid.add(current);
            }
            List<Boolean> results = vectorStore.upsertBatch(valid);
            for (int i = 0; i < valid.size(); i++) {
                // 向量库未就绪时 upsertBatch 返回空列表，无守卫会 IndexOutOfBounds → 500。
                // / upsertBatch returns an empty list when the store is not ready; guard it.
                boolean ok = i < results.size() && results.get(i);
                repository.updateStatus(valid.get(i).getId(), ok ? "ready" : "vector_failed");
                if (ok) success++; else failed++;
            }
            log.info("[Knowledge] reindex done: total={} success={} failed={}", targets.size(), success, failed);
            // 向量/倒排重建不改变知识内容与图谱结构，无需通知图谱（避免全量重建）。
            // Vector/inverted-index rebuild does not change knowledge content or graph structure;
            // no event is emitted, avoiding a costly full graph rebuild.
            return Map.of("total", targets.size(), "success", success, "failed", failed);
        }
    }

    /** 删除一条知识（同时删向量）/ Delete an entry (vector included). */
    public void delete(String id) {
        // 与 reindex 互斥：reindex 在「findById 复核 → upsertBatch」窗口内，
        // 并发 delete 会产生幽灵向量（元数据已删、向量仍在）。/ Mutual exclusion
        // with reindex: a concurrent delete inside reindex's recheck→upsert window
        // leaves a ghost vector.
        synchronized (reindexLock) {
            repository.delete(id);
            vectorStore.delete(id);
        }
        publishChange(KnowledgeChangedEvent.ChangeType.DELETE, id);
    }

    /**
     * 恢复软删除的知识并重建向量 / Restore a soft-deleted entry and rebuild its vector.
     *
     * <p>delete() 会删除向量，恢复时必须重新写入，否则元数据可见但检索命中不到。
     * / delete() removes the vector, so restore must rebuild it or retrieval stays inconsistent.
     */
    public boolean restore(String id) {
        KnowledgeEntry e = repository.findByIdIncludingDeleted(id);
        if (e == null || !e.isDeleted()) return false;
        synchronized (reindexLock) {
            repository.restore(id);
            // 重建倒排索引 / Rebuild inverted index.
            repository.indexTerms(id, tokenizer.tokenize(e.getTitle() + " " + e.getContent()));
            // 重建向量：先标记 pending，按结果更新状态 / Rebuild vector: pending first, then status by result.
            repository.updateStatus(id, "pending");
            boolean ok = vectorStore.upsertWithResult(e);
            repository.updateStatus(id, ok ? "ready" : "vector_failed");
        }
        publishChange(KnowledgeChangedEvent.ChangeType.ADD, id);
        return true;
    }

    /** 按来源删除 / Delete by source ID. */
    public int deleteBySource(String sourceId) {
        List<String> ids;
        synchronized (reindexLock) {
            ids = repository.deleteBySource(sourceId);
            vectorStore.deleteBySource(sourceId);
        }
        // 按实际删除 ID 增量移除图谱子图 / Incrementally remove graph subgraphs by deleted IDs.
        publishChange(KnowledgeChangedEvent.ChangeType.DELETE_BATCH, ids);
        return ids.size();
    }

    /**
     * 不区分大小写读取 Map 值 / Case-insensitive map lookup.
     * H2 返回大写列名，SQLite 返回原样，需兼容 / H2 returns uppercase keys, SQLite preserves case.
     */
    private Object mapValue(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    /** 计算 SHA-256 哈希 / Compute SHA-256 hash. */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 实际不可达；一旦发生必须显式失败，而不是返回随机 UUID——
            // 那会让去重静默失效（每条内容都被当作"新内容"）。
            // / SHA-256 is effectively infallible; on the impossible failure, fail loudly
            // rather than returning a random UUID, which would silently defeat dedup.
            throw new IllegalStateException("Failed to compute SHA-256 hash", e);
        }
    }

    /**
     * 录入期公式语法校验 / Validate formula syntax at ingestion time.
     *
     * <p>仅校验语法（不校验字段存在性——字段在执行期结合具体数据集判断）。
     * / Syntax only; column existence is checked at execution time per dataset.
     *
     * @param formula 公式表达式（可空）/ Formula expression (nullable).
     * @throws IllegalArgumentException 语法错误 / On syntax errors.
     */
    private void validateFormula(String formula) {
        if (formula != null && !formula.isBlank()) {
            FormulaCompiler.validateSyntax(formula);
        }
    }

    /**
     * 公式注册表（标题 → 表达式），供确定性指标编译 / Formula registry
     * (title → expression) powering deterministic metric compilation.
     *
     * <p>每次分析时即时查询：公式条目数量级小（数十），H2/SQLite 查询
     * 毫秒级，无需缓存及其失效逻辑。/ Queried per analysis: formula entries are
     * few (tens at most), the query is sub-millisecond, and skipping the cache
     * avoids invalidation complexity.
     *
     * @return 标题 → 表达式 / title-to-expression mapping.
     */
    public java.util.Map<String, String> formulaIndex() {
        return repository.listFormulas();
    }
}

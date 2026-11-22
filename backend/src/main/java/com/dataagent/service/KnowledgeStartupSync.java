package com.dataagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时知识库同步 / Knowledge base startup sync.
 *
 * <p>应用就绪后异步重试向量写入失败的条目（status=pending/vector_failed），
 * 消除"上次因 Embedding 服务不可用而失败、恢复后要手动 reindex"的运维负担。
 * / After the app is ready, asynchronously retry entries whose vector write failed,
 * removing the need to manually run reindex after an embedding outage recovers.
 */
@Slf4j
@Component
public class KnowledgeStartupSync {

    private final KnowledgeService knowledgeService;
    private final KnowledgeVectorStore vectorStore;

    public KnowledgeStartupSync(KnowledgeService knowledgeService, KnowledgeVectorStore vectorStore) {
        this.knowledgeService = knowledgeService;
        this.vectorStore = vectorStore;
    }

    /**
     * 应用就绪后重试 pending 向量 / Retry pending vectors after app is ready.
     *
     * <p>用异步线程执行，避免阻塞启动；只重试 pending/failed 条目，不动已 ready 的。
     * / Runs asynchronously so startup is not blocked; only retries pending/failed entries.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void retryPendingVectors() {
        new Thread(() -> {
            try {
                // 新建/迁移的 collection 是空的，需全量重建；否则只重试失败条目。
                // A newly created (migrated) collection is empty: rebuild all; otherwise retry failures.
                boolean force = vectorStore.isNeedsFullReindex();
                if (force) log.info("[StartupSync] new collection detected, full reindex");
                java.util.Map<String, Object> result = knowledgeService.reindex(force);
                int total = (int) result.getOrDefault("total", 0);
                if (total > 0) {
                    log.info("[StartupSync] vector {}: total={} success={} failed={}",
                            force ? "rebuild" : "retry", total, result.get("success"), result.get("failed"));
                }
            } catch (Exception e) {
                log.warn("[StartupSync] vector sync failed: {}", e.getMessage());
            }
        }, "knowledge-startup-sync").start();
    }
}

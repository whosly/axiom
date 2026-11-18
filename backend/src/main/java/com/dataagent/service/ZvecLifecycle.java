package com.dataagent.service;

import com.dataagent.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.zvec.binding.Zvec;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Zvec 全局生命周期管理 / Global Zvec lifecycle manager.
 *
 * <p>Zvec 的 native 库要求进程内只调用一次 initialize/shutdown。本组件独占这两个调用，
 * 供多个 collection 服务（数据集语义、知识库）共享，避免重复初始化导致的 native 异常。
 * The Zvec native library requires initialize/shutdown to be called exactly once per
 * process. This component owns those calls so that multiple collection services
 * (dataset semantics, knowledge base) can share one runtime without double initialization.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZvecLifecycle {

    private final AppProperties props;

    /** 标记 native 运行时是否已成功初始化 / Whether the native runtime was initialized successfully. */
    private boolean initialized = false;

    /** 应用启动时初始化 Zvec runtime / Initialize the Zvec runtime on application startup. */
    @PostConstruct
    public void init() {
        try {
            // 只创建两个 collection 的父目录（data/zvec），不创建 collection 目录本身，
            // 因为 Zvec.createAndOpen 要求目标目录不存在 / Create only the parent dir; Zvec.createAndOpen
            // requires the collection directory itself to be absent.
            Path parent = Path.of(props.getZvec().getPath()).toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Zvec.initialize(null);
            initialized = true;
            log.info("[Zvec] runtime initialized, version={}", Zvec.getVersion());
        } catch (Throwable t) {
            // 已初始化时不视为错误，避免多实例重复调用 / Already-initialized is not an error.
            log.warn("[Zvec] runtime init skipped: {}", t.getMessage());
        }
    }

    /** 应用关闭时释放 Zvec runtime / Shut down the Zvec runtime on application shutdown. */
    @PreDestroy
    public void shutdown() {
        if (!initialized) return;
        try {
            Zvec.shutdown();
            log.info("[Zvec] runtime shut down");
        } catch (Throwable t) {
            log.warn("[Zvec] shutdown error: {}", t.getMessage());
        }
    }

    /** 查询运行时是否可用 / Whether the runtime is available. */
    public boolean isInitialized() { return initialized; }
}

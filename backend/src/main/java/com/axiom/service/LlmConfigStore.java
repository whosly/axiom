package com.axiom.service;

import com.axiom.config.AppProperties;
import com.axiom.llm.LlmProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 运行时 LLM 配置持久化 / Runtime LLM configuration persistence.
 *
 * <p>把通过配置面板热更新的 provider 选择与各厂商配置写入 data/llm-config.json，
 * 应用启动时加载覆盖。**API Key 不落盘**：密钥只从环境变量/application.yml 提供，
 * 避免明文凭据 leaked 到数据目录或 git。持久化文件是「用户运行期非敏感覆盖」，优先级更高。
 *
 * Persists runtime-updated provider/model selection to data/llm-config.json and reloads on
 * startup. API keys are intentionally NOT persisted; they stay in env/yml only so no plaintext
 * credential is written to disk.
 */
@Slf4j
@Component
public class LlmConfigStore {

    private final AppProperties props;
    private final ObjectMapper mapper;

    @Value("${app.storage.data-dir:./data}")
    private String dataDir;

    private Path configFile() {
        return Path.of(dataDir, "llm-config.json");
    }

    public LlmConfigStore(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /** 启动时加载持久化配置 / Load persisted config on startup. */
    @PostConstruct
    public void load() {
        Path file = configFile();
        if (!Files.exists(file)) return;
        try {
            Persisted p = mapper.readValue(file.toFile(), Persisted.class);
            if (p == null) return;
            if (p.provider != null && !p.provider.isBlank()) props.getLlm().setProvider(p.provider);
            if (p.providers != null) {
                p.providers.forEach((name, cfg) -> {
                    if (cfg != null) props.getLlm().config(name).mergeFrom(cfg);
                });
            }
            log.info("[LLMConfig] loaded persisted config from {} (provider={})", file, props.getLlm().getProvider());
        } catch (Exception e) {
            log.warn("[LLMConfig] failed to load {}: {}", file, e.getMessage());
        }
    }

    /** 保存当前配置到磁盘 / Persist current config to disk. */
    public synchronized void save() {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            Persisted p = new Persisted();
            p.provider = props.getLlm().getProvider();
            // 深拷贝并清空 apiKey：密钥只保留在内存与 env/yml，不写盘。
            // Deep-copy and blank apiKey: credentials stay in memory and env/yml, never on disk.
            p.providers = new java.util.LinkedHashMap<>();
            props.getLlm().getProviders().forEach((name, cfg) -> {
                LlmProviderConfig safe = new LlmProviderConfig();
                safe.setBaseUrl(cfg.getBaseUrl());
                safe.setChatModel(cfg.getChatModel());
                safe.setReasonerModel(cfg.getReasonerModel());
                safe.setTemperature(cfg.getTemperature());
                safe.setMaxTokens(cfg.getMaxTokens());
                p.providers.put(name, safe);
            });
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), p);
            try {
                Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions
                        .fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows 等不支持 POSIX 权限的平台跳过 / Skip on filesystems without POSIX perms.
            }
            log.debug("[LLMConfig] persisted config to {}", file);
        } catch (IOException e) {
            log.warn("[LLMConfig] failed to persist {}: {}", file, e.getMessage());
        }
    }

    /** 持久化结构 / Persisted structure. */
    public static class Persisted {
        public String provider;
        public Map<String, LlmProviderConfig> providers;
    }
}

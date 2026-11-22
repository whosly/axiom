package com.dataagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private Storage storage = new Storage();
    private Zvec zvec = new Zvec();
    private Llm llm = new Llm();
    /** 重排序配置 / Rerank configuration. */
    private Rerank rerank = new Rerank();
    /** Embedding 配置（独立策略）/ Embedding configuration (independent strategy). */
    private Embedding embedding = new Embedding();

    /**
     * Embedding 配置 / Embedding configuration.
     *
     * <p>provider: local（本地 ONNX）| api（远程 OpenAI 兼容端点）。
     * local 模式零外部依赖，适合离线与隐私场景。
     * / provider: local (local ONNX) | api (remote OpenAI-compatible endpoint).
     * local mode has zero external dependency, ideal for offline/privacy scenarios.
     */
    @Data
    public static class Embedding {
        /** 策略选择：local | api / Strategy selector. */
        private String provider = "local";
        /** 本地 ONNX 模型配置 / Local ONNX model configuration. */
        private Local local = new Local();
        /** 远程 API 配置（provider=api 时生效）/ Remote API config (effective when provider=api). */
        private Api api = new Api();

        /** OpenAI 兼容 embedding API 配置 / OpenAI-compatible embedding API config. */
        @Data
        public static class Api {
            private String baseUrl = "https://api.siliconflow.cn/v1";
            private String apiKey = "";
            private String model = "BAAI/bge-m3";
            private int dimension = 512;
        }

        /** 本地 embedding 模型配置 / Local embedding model configuration. */
        @Data
        public static class Local {
            /** ONNX 模型路径 / ONNX model path. */
            private String modelPath = "./data/models/bge-small-zh-v1.5/model.onnx";
            /** vocab.txt 路径 / vocab.txt path. */
            private String vocabPath = "./data/models/bge-small-zh-v1.5/vocab.txt";
            /** 向量维度（bge-small-zh-v1.5 = 512）/ Vector dimension. */
            private int dimension = 512;
            /** 最大序列长度 / Max sequence length. */
            private int maxLength = 512;
            /** ONNX 推理线程数 / ONNX inference threads. */
            private int threads = 2;
        }
    }

    /** 重排序配置 / Rerank configuration. */
    @Data
    public static class Rerank {
        /** 策略：auto | llm | rule / Strategy selector. */
        private String provider = "auto";
        /** 是否启用 / Whether enabled. */
        private boolean enabled = true;
        /** 重排候选数 / Number of candidates to rerank. */
        private int candidateSize = 20;
        /** 注入 prompt 的知识上下文字符预算 / Char budget of knowledge context injected into prompt. */
        private int contextCharBudget = 2000;
    }

    /**
     * 存储路径配置 / Storage path configuration.
     * 每种存储独立子目录，避免 data 根目录混乱 / Each store lives in its own subdirectory.
     */
    @Data
    public static class Storage {
        /** 数据根目录 / Data root directory. */
        private String dataDir = "./data";
        /** DuckDB 分析库路径 / DuckDB analytics database path. */
        private String duckdbPath = "./data/duckdb/analysis.duckdb";
        /** 元数据库类型：h2 | sqlite / Metadata store type. */
        private String metadataType = "h2";
        /** H2 连接串 / H2 JDBC URL. */
        private String h2Url = "jdbc:h2:file:./data/h2/metadata;AUTO_SERVER=TRUE";
        /** SQLite 连接串 / SQLite JDBC URL. */
        private String sqliteUrl = "jdbc:sqlite:./data/sqlite/metadata.db";
        /** 数据库用户名 / DB username. */
        private String username = "sa";
        /** 数据库密码 / DB password. */
        private String password = "";
    }

    /**
     * Zvec 向量库配置 / Zvec vector store configuration.
     *
     * <p>维护两个独立的 collection：数据集语义（字段/历史/结论）和知识库。
     * Maintains two independent collections: dataset semantics (fields/history/conclusions)
     * and the knowledge base.
     */
    @Data
    public static class Zvec {
        /** 是否启用向量库 / Whether the vector store is enabled. */
        private boolean enabled = true;
        /** 数据集语义 collection 存储路径 / Storage path of the dataset-semantics collection. */
        private String path = "./data/zvec/dataset_semantics";
        /** 向量维度（bge-small-zh-v1.5 = 512）/ Vector dimension. */
        private int dimension = 512;
        /** 数据集语义 collection 名称 / Name of the dataset-semantics collection. */
        private String collection = "dataset_semantics";

        /** 知识库 collection 存储路径（v2 增加 FTS 索引，路径带版本便于迁移）
         *  Storage path (v2 adds FTS index; version suffix enables migration). */
        private String knowledgePath = "./data/zvec/knowledge_base_v2";
        /** 知识库 collection 名称 / Name of the knowledge-base collection. */
        private String knowledgeCollection = "knowledge_base_v2";
    }

    @Data
    public static class Llm {
        /** 当前选择的厂商策略名 / Active vendor strategy name. */
        private String provider = "deepseek";

        /** 各厂商配置，键为策略名（deepseek/qwen），由 app.llm.providers.<name>.* 绑定
         *  Per-vendor config keyed by strategy name, bound from app.llm.providers.<name>.*. */
        private java.util.Map<String, com.dataagent.llm.LlmProviderConfig> providers = new java.util.LinkedHashMap<>();

        /**
         * 获取指定厂商配置，不存在时创建并返回一个空配置（含默认模型名）。
         * Get config for a vendor; create a blank one with a default model if missing.
         *
         * @param name 厂商策略名 / Vendor strategy name
         * @return 厂商配置（永不为 null）/ Vendor config (never null)
         */
        public com.dataagent.llm.LlmProviderConfig config(String name) {
            return providers.computeIfAbsent(name, k -> new com.dataagent.llm.LlmProviderConfig());
        }

        /** 当前厂商配置 / Config of the active vendor. */
        public com.dataagent.llm.LlmProviderConfig currentConfig() {
            return config(provider);
        }
    }
}

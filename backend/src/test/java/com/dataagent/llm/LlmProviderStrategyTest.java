package com.dataagent.llm;

import com.dataagent.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 策略模式测试 / Tests for the LLM strategy pattern.
 *
 * <p>验证工厂自动发现、按配置选择、以及 DeepSeek/通义共享配置结构且各自独立。
 * Verifies auto-discovery, config-based selection, and independent per-vendor config.
 */
class LlmProviderStrategyTest {

    private AppProperties props;
    private LlmProviderFactory factory;

    /** 构造两个真实 provider 与工厂 / Build two real providers and the factory. */
    @BeforeEach
    void setUp() {
        props = new AppProperties();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, LlmProvider> providers = new LinkedHashMap<>();
        providers.put("deepseek", new DeepSeekProvider(props, mapper));
        providers.put("qwen", new QwenProvider(props, mapper));
        factory = new LlmProviderFactory(providers, props);

        // 各自配置独立 / Independent configs for each vendor.
        LlmProviderConfig ds = props.getLlm().config("deepseek");
        ds.setBaseUrl("https://api.deepseek.com");
        ds.setChatModel("deepseek-chat");
        ds.setApiKey("sk-ds");
        LlmProviderConfig qwen = props.getLlm().config("qwen");
        qwen.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        qwen.setChatModel("qwen-plus");
        qwen.setApiKey("sk-qwen");
    }

    /** 默认选中 deepseek / Default selection is deepseek. */
    @Test
    @DisplayName("工厂按配置选中 deepseek")
    void selectsConfiguredProvider() {
        assertThat(factory.current().providerName()).isEqualTo("deepseek");
        assertThat(factory.current().displayName()).isEqualTo("DeepSeek");
    }

    /** 切换配置后工厂选中 qwen / Switching config selects qwen. */
    @Test
    @DisplayName("切换 provider 配置后选中 qwen")
    void switchesToQwen() {
        props.getLlm().setProvider("qwen");
        assertThat(factory.current().providerName()).isEqualTo("qwen");
        assertThat(factory.current().chatModel()).isEqualTo("qwen-plus");
        assertThat(factory.current().isAvailable()).isTrue();
    }

    /** 各厂商配置互相隔离 / Each vendor's config is isolated. */
    @Test
    @DisplayName("厂商配置互相隔离")
    void configsAreIsolated() {
        props.getLlm().config("deepseek").setChatModel("custom-ds");
        assertThat(props.getLlm().config("qwen").getChatModel()).isEqualTo("qwen-plus");
        assertThat(factory.get("deepseek").chatModel()).isEqualTo("custom-ds");
    }

    /** 无 Key 时 isAvailable 为 false，触发规则降级 / No key means unavailable (rule fallback). */
    @Test
    @DisplayName("无 API Key 时不可用")
    void unavailableWithoutKey() {
        props.getLlm().config("qwen").setApiKey("");
        assertThat(factory.get("qwen").isAvailable()).isFalse();
    }

    /** 模型选项动态列出全部已注册策略 / Model options list all registered providers. */
    @Test
    @DisplayName("模型选项包含两个策略")
    void listsAllOptions() {
        var options = factory.options();
        assertThat(options).hasSize(2);
        assertThat(options).anySatisfy(o -> assertThat(o.get("modelId")).isEqualTo("qwen"));
    }

    /** 未知策略名回退到第一个可用 provider / Unknown name falls back to first provider. */
    @Test
    @DisplayName("未知策略名回退")
    void fallsBackOnUnknownName() {
        props.getLlm().setProvider("unknown");
        assertThat(factory.current().providerName()).isEqualTo("deepseek");
    }
}

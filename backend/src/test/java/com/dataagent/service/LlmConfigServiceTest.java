package com.dataagent.service;

import com.dataagent.config.AppProperties;
import com.dataagent.llm.DeepSeekProvider;
import com.dataagent.llm.LlmProvider;
import com.dataagent.llm.LlmProviderFactory;
import com.dataagent.llm.LlmProviderConfig;
import com.dataagent.llm.QwenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行时 LLM 配置服务测试 / Tests for runtime LLM configuration.
 *
 * <p>验证热更新作用于当前厂商、切换厂商、以及 API Key 脱敏。
 * Verifies hot-update targets the active vendor, provider switching, and key masking.
 */
class LlmConfigServiceTest {

    private AppProperties props;
    private LlmConfigService service;

    /** 构造服务与两个 provider / Build service with two providers. */
    @BeforeEach
    void setUp() {
        props = new AppProperties();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, LlmProvider> providers = new LinkedHashMap<>();
        providers.put("deepseek", new DeepSeekProvider(props, mapper));
        providers.put("qwen", new QwenProvider(props, mapper));
        LlmProviderFactory factory = new LlmProviderFactory(providers, props);

        LlmProviderConfig ds = props.getLlm().config("deepseek");
        ds.setBaseUrl("https://api.deepseek.com");
        ds.setChatModel("deepseek-chat");
        ds.setApiKey("sk-deepseek-secret-1234");
        LlmProviderConfig qwen = props.getLlm().config("qwen");
        qwen.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        qwen.setChatModel("qwen-plus");
        qwen.setApiKey("sk-qwen-secret-5678");

        service = new LlmConfigService(props, factory);
    }

    /** 当前配置脱敏展示末四位 / Current config masks the key to last 4 chars. */
    @Test
    @DisplayName("配置脱敏仅显示末四位")
    void currentMasksApiKey() {
        Map<String, Object> current = service.current();
        assertThat(current.get("provider")).isEqualTo("deepseek");
        assertThat(current.get("apiKeyMasked")).isEqualTo("****1234");
        assertThat(current.get("apiKeySet")).isEqualTo(true);
        assertThat(current.get("displayName")).isEqualTo("DeepSeek");
    }

    /** 更新作用当前厂商，不影响其他厂商 / Update targets active vendor only. */
    @Test
    @DisplayName("热更新只改当前厂商配置")
    void updateTargetsActiveVendor() {
        service.update(Map.of("temperature", "0.8", "chatModel", "deepseek-custom"));
        assertThat(props.getLlm().config("deepseek").getTemperature()).isEqualTo(0.8);
        assertThat(props.getLlm().config("deepseek").getChatModel()).isEqualTo("deepseek-custom");
        assertThat(props.getLlm().config("qwen").getChatModel()).isEqualTo("qwen-plus");
    }

    /** 切换到 qwen 后更新作用于 qwen / After switching, update targets qwen. */
    @Test
    @DisplayName("切换厂商后热更新作用于新厂商")
    void switchThenUpdate() {
        service.update(Map.of("provider", "qwen", "chatModel", "qwen-max"));
        assertThat(props.getLlm().getProvider()).isEqualTo("qwen");
        assertThat(props.getLlm().config("qwen").getChatModel()).isEqualTo("qwen-max");
        assertThat(props.getLlm().config("deepseek").getChatModel()).isEqualTo("deepseek-chat");
        assertThat(service.current().get("provider")).isEqualTo("qwen");
    }

    /** 切换到未知厂商抛异常 / Switching to unknown provider throws. */
    @Test
    @DisplayName("切换未知厂商抛异常")
    void rejectsUnknownProvider() {
        assertThatThrownBy(() -> service.update(Map.of("provider", "unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown LLM provider");
    }
}

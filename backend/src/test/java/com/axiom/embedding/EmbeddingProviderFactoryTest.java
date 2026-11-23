package com.axiom.embedding;

import com.axiom.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Embedding 策略工厂测试 / Tests for the embedding strategy factory.
 */
class EmbeddingProviderFactoryTest {

    /** local 别名映射到 localEmbedding bean / local alias maps to localEmbedding bean. */
    @Test
    @DisplayName("local 策略映射")
    void localAlias() {
        AppProperties props = new AppProperties();
        props.getEmbedding().setProvider("local");
        EmbeddingProvider local = mock(EmbeddingProvider.class);
        Map<String, EmbeddingProvider> providers = new LinkedHashMap<>();
        providers.put("localEmbedding", local);
        assertThat(new EmbeddingProviderFactory(providers, props).current()).isSameAs(local);
    }

    /** api 别名映射到 apiEmbedding bean / api alias maps to apiEmbedding bean. */
    @Test
    @DisplayName("api 策略映射")
    void apiAlias() {
        AppProperties props = new AppProperties();
        props.getEmbedding().setProvider("api");
        EmbeddingProvider api = mock(EmbeddingProvider.class);
        Map<String, EmbeddingProvider> providers = new LinkedHashMap<>();
        providers.put("apiEmbedding", api);
        assertThat(new EmbeddingProviderFactory(providers, props).current()).isSameAs(api);
    }

    /** 未知策略抛异常 / Unknown strategy throws. */
    @Test
    @DisplayName("未知策略抛异常")
    void unknownStrategy() {
        AppProperties props = new AppProperties();
        props.getEmbedding().setProvider("unknown");
        assertThatThrownBy(() -> new EmbeddingProviderFactory(Map.of(), props).current())
                .isInstanceOf(IllegalArgumentException.class);
    }
}

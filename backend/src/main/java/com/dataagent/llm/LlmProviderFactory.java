package com.dataagent.llm;

import com.dataagent.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/** Strategy factory: selects the configured LLM provider at runtime. */
@Component
public class LlmProviderFactory {

    private final Map<String, ? extends LlmProvider> providers;
    private final AppProperties props;

    public LlmProviderFactory(Map<String, LlmProvider> providers, AppProperties props) {
        this.providers = providers;
        this.props = props;
    }

    public LlmProvider current() {
        String name = props.getLlm().getProvider();
        LlmProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown LLM provider: " + name
                    + ". Available: " + providers.keySet());
        }
        return provider;
    }
}

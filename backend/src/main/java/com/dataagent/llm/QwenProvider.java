package com.dataagent.llm;

import com.dataagent.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 通义千问对话策略 / Qwen (Tongyi Qianwen) chat strategy.
 *
 * <p>阿里云百炼（DashScope）提供 OpenAI 兼容模式：
 * base-url = https://dashscope.aliyuncs.com/compatible-mode/v1，
 * 默认模型 qwen-plus / qwen-max，协议与 DeepSeek 一致，复用基类实现。
 * DashScope offers an OpenAI-compatible mode; protocol handling is inherited from the base class.
 */
@Component("qwen")
public class QwenProvider extends OpenAiCompatibleProvider {

    public QwenProvider(AppProperties props, ObjectMapper mapper) {
        super(props, mapper, "qwen");
    }

    @Override
    public String displayName() { return "通义千问 / Qwen"; }
}

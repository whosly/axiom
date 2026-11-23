# LLM 厂商与模型扩展指南 / LLM Provider & Model Extension Guide

> **新增一个模型**（同一厂商换模型）→ 只改配置，不写代码。
> **新增一个厂商** → 加一个极简 Java 类 + 配置块，工厂自动发现，零工厂改动。
>
> Add a **model** (same vendor) → config only, no code.
> Add a **vendor** → one tiny Java class + config block; factory auto-discovers it.

目录 / TOC：
- [1. 两种场景速查 / Two scenarios at a glance](#1-两种场景速查--two-scenarios-at-a-glance)
- [2. 场景 A：新增/切换模型（零代码）/ Add a model](#2-场景-a新增切换模型零代码--scenario-a-add-a-model)
- [3. 场景 B：新增 OpenAI 兼容厂商（3 步）/ Add a vendor](#3-场景-b新增-openai-兼容厂商3-步--scenario-b-add-an-openai-compatible-vendor)
- [4. 场景 C：新增非 OpenAI 协议厂商 / Non-OpenAI vendor](#4-场景-c新增非-openai-协议厂商--scenario-c-add-a-non-openai-protocol-vendor)
- [5. 运行时热配置 API / Runtime config API](#5-运行时热配置-api--runtime-config-api)
- [6. 策略模式结构与扩展点 / Strategy structure](#6-策略模式结构与扩展点--strategy-structure--extension-points)
- [7. 常见问题 / FAQ](#7-常见问题--faq)

---

## 1. 两种场景速查 / Two scenarios at a glance

| 你要做的事 | 需要改什么 | 需要写代码吗 |
|---|---|---|
| 同一厂商换模型（如 deepseek-chat → deepseek-reasoner） | 改 `chatModel` 配置 / 前端下拉切换 | ❌ 零代码 |
| 新增一个 OpenAI 兼容厂商（如 Kimi、智谱、OpenAI） | 1 个 Provider 类 + 1 个配置块 | ✅ 约 5 行代码 |
| 新增非 OpenAI 协议厂商 | 实现 `LlmProvider` 接口 | ✅ 完整实现 |

关键原则 / Key principle：所有 OpenAI 兼容厂商（`/chat/completions` + SSE `data:` 格式）共享
`OpenAiCompatibleProvider` 基类，子类只需声明策略名。工厂 `LlmProviderFactory` 从 Spring 容器
**自动发现所有 `LlmProvider` bean**，因此新增厂商不需要改工厂或任何调用方。
All OpenAI-compatible vendors share the base class; the factory auto-discovers beans,
so no factory or caller changes are needed.

---

## 2. 场景 A：新增/切换模型（零代码）/ Scenario A: Add a model

同一个厂商下换用不同模型（包括厂商发布的新模型），**只需改配置**，无需写 Java 代码。

### 方式一：环境变量 / Env vars

```bash
# DeepSeek 换模型
DEEPSEEK_CHAT_MODEL=deepseek-chat          # 日常对话模型
DEEPSEEK_REASONER_MODEL=deepseek-reasoner  # 推理模型（保留字段）

# 通义换模型
QWEN_CHAT_MODEL=qwen-plus
QWEN_REASONER_MODEL=qwen-max
```

### 方式二：配置文件 / application.yml

```yaml
app:
  llm:
    provider: deepseek          # 当前使用哪个厂商
    providers:
      deepseek:
        chat-model: deepseek-chat       # 改这里即可换模型
        reasoner-model: deepseek-reasoner
```

### 方式三：前端运行时热切换（无需重启）/ UI hot-switch

打开页面 → 输入框上方「模型 / Model」下拉选择厂商；
或点「配置 / Config」→ 在弹窗中修改 Chat 模型 / Reasoner 模型，保存即生效。
These call `POST /api/config/llm` and take effect immediately.

### 生效范围 / Scope

`chatModel` 同时用于两处：
- `AnalysisOrchestrator.plan()` — 自然语言 → 结构化分析计划
- `AnalysisOrchestrator.conclude()` — 结果 → 结论文本

`reasonerModel` 是保留字段，供将来区分推理/对话流程使用，当前不参与调用。

---

## 3. 场景 B：新增 OpenAI 兼容厂商（3 步）/ Scenario B: Add an OpenAI-compatible vendor

以新增 **Kimi（月之暗面）** 为例，其 API 是 OpenAI 兼容的。
Example: adding Kimi, whose API is OpenAI-compatible.

### 步骤 1：新增 Provider 类（约 5 行）/ Add a Provider class

创建 `backend/src/main/java/com/axiom/llm/KimiProvider.java`：

```java
package com.axiom.llm;

import com.axiom.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component("kimi")                          // ① 策略名 = bean 名
public class KimiProvider extends OpenAiCompatibleProvider {
    public KimiProvider(AppProperties props, ObjectMapper mapper) {
        super(props, mapper, "kimi");       // ② 与 @Component 名一致
    }
    @Override
    public String displayName() { return "Kimi / 月之暗面"; }  // ③ 前端展示名
}
```

就这三处。**请求构建、SSE 流式解析、鉴权头全部由基类处理。**
That's it. Request building, SSE streaming and auth headers are inherited.

### 步骤 2：新增配置块 / Add config block

在 `backend/src/main/resources/application.yml` 的 `app.llm.providers` 下追加：

```yaml
app:
  llm:
    providers:
      kimi:
        base-url: ${KIMI_BASE_URL:https://api.moonshot.cn/v1}
        api-key: ${KIMI_API_KEY:}
        chat-model: ${KIMI_CHAT_MODEL:moonshot-v1-8k}
        reasoner-model: ${KIMI_REASONER_MODEL:moonshot-v1-32k}
        temperature: ${KIMI_TEMPERATURE:0.1}
        max-tokens: ${KIMI_MAX_TOKENS:4096}
```

同时在 `.env.example` 补充对应环境变量（供 Docker 部署使用）。

### 步骤 3（可选）：选择它 / Select it

```bash
LLM_PROVIDER=kimi
```

或不改环境变量，直接在前端「配置」面板切换厂商（会调用 `POST /api/config/llm`）。

### 验证 / Verify

```bash
curl http://localhost:8080/api/models
# {"models":[...,{"modelId":"kimi","name":"Kimi / 月之暗面"}],"defaultModel":"deepseek"}

curl http://localhost:8080/api/config/llm   # 查看当前配置（密钥脱敏）
```

前端模型下拉会自动出现新厂商——因为 `/api/models` 读取的是工厂动态发现结果。

### 如果厂商不是标准 `/chat/completions` 路径 / Custom endpoint

基类默认拼接 `base-url + "/chat/completions"`。若厂商路径不同，覆写 `endpoint()`：

```java
@Override
protected String endpoint() { return "/v1/chat"; }
```

---

## 4. 场景 C：新增非 OpenAI 协议厂商 / Scenario C: Non-OpenAI vendor

若厂商协议完全不同（自定义请求/响应格式），直接实现 `LlmProvider` 接口：

```java
@Component("myvendor")
public class MyVendorProvider implements LlmProvider {
    @Override public String providerName() { return "myvendor"; }
    @Override public String displayName() { return "My Vendor"; }
    @Override public boolean isAvailable() { /* 检查 Key */ }
    @Override public String chatModel() { /* 当前模型名 */ }
    @Override public String chat(List<ChatMessage> msgs, ChatOptions o) { /* 实现 */ }
    @Override public void chatStream(List<ChatMessage> msgs, ChatOptions o, Consumer<String> sink) { /* 实现 */ }
}
```

同样在 `application.yml` 加配置块（`app.llm.providers.myvendor.*`）。
工厂、编排器、Rerank、配置服务**均无需改动**（它们只依赖接口方法）。

---

## 5. 运行时热配置 API / Runtime config API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/models` | 已注册厂商列表（动态发现）+ 当前默认 |
| GET | `/api/config/llm` | 当前厂商配置（API Key 脱敏）+ 可选厂商列表 |
| POST | `/api/config/llm` | 热更新配置 / 切换厂商，立即生效 |

POST 请求体字段（均为可选）：

```json
{
  "provider": "qwen",
  "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
  "apiKey": "sk-xxx",
  "chatModel": "qwen-plus",
  "reasonerModel": "qwen-max",
  "temperature": "0.1",
  "maxTokens": "4096"
}
```

- `provider` 单独用于切换策略；其余字段写入**当前厂商**的配置
- 切换到未注册厂商会返回 400 并列出可用厂商
- `apiKey` 留空表示不修改；返回的 `apiKeyMasked` 只显示末 4 位
- 切换厂商后，`/api/health` 的 `llmProvider` 字段同步变化

---

## 6. 策略模式结构与扩展点 / Strategy structure & extension points

```
com.axiom.llm/
├── LlmProvider.java              接口：chat 职责 + isAvailable/chatModel/displayName
├── LlmProviderConfig.java        所有厂商共享的配置结构
├── OpenAiCompatibleProvider.java OpenAI 兼容基类（请求构建 + SSE 解析）
├── DeepSeekProvider.java         @Component("deepseek") —— 极简子类
├── QwenProvider.java             @Component("qwen") —— 极简子类
└── LlmProviderFactory.java       从 Spring 容器自动发现，按 provider 配置选择
```

调用方全部依赖接口，**零厂商硬编码**：
- `AnalysisOrchestrator` — `llmFactory.current()` + `llm.chatModel()`，`llmFactory.isAvailable()` 判定降级
- `RerankService` / `LlmRerankProvider` — 同样委托工厂
- `LlmConfigService` / `AnalysisController` — 动态列出/切换厂商

配置类 / Config classes：
- `AppProperties.Llm.provider` — 当前策略名
- `AppProperties.Llm.providers` — `Map<String, LlmProviderConfig>`，YAML 中 `app.llm.providers.<name>.*`
- `AppProperties.Embedding.api` — 远程 embedding（与对话厂商解耦，独立配置）

---

## 7. 常见问题 / FAQ

**Q：加了配置块但 `/api/models` 没出现新厂商？**
A：缺少 `@Component("名字")` 的 Provider bean。工厂只发现 Spring bean，不读配置块自动建 bean。

**Q：新增厂商后无 Key 会怎样？**
A：`isAvailable()` 返回 false，系统自动降级到规则引擎（Planner / 结论 / Rerank），核心功能可用。

**Q：DeepSeek 没有 embedding，通义有，怎么配？**
A：embedding 与对话完全解耦，走 `app.embedding.provider`（`local` 或 `api`），与 `app.llm.providers` 无关。

**Q：运行时切换会持久化吗？**
A：不会。热更新只改内存中的配置 bean，重启后回到 `application.yml` / 环境变量的值。要持久化请改配置文件。

**Q：如何为新厂商写测试？**
A：参考 `backend/src/test/java/com/axiom/llm/LlmProviderStrategyTest.java`，构造 AppProperties + 两个真实 Provider + 工厂即可验证发现/切换/隔离。

package com.dataagent.controller;

import com.dataagent.llm.LlmProviderFactory;
import com.dataagent.model.*;
import com.dataagent.repository.MetadataRepository;
import com.dataagent.service.AnalysisOrchestrator;
import com.dataagent.service.LlmConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SSE streaming chat that unifies plan / result / chart / conclusion into one conversation. */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisOrchestrator orchestrator;
    private final MetadataRepository metaRepo;
    private final ObjectMapper mapper;
    private final LlmProviderFactory llmFactory;
    private final LlmConfigService llmConfigService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "timestamp", LocalDateTime.now().toString(),
                "llmProvider", llmFactory.current().providerName());
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        return Map.of(
                "models", List.of(
                        Map.of("modelId", "deepseek-chat", "name", "DeepSeek Chat"),
                        Map.of("modelId", "deepseek-reasoner", "name", "DeepSeek Reasoner")),
                "defaultModel", "deepseek-chat");
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(300_000L);

        String sessionId = (String) body.get("sessionId");
        String datasetId = (String) body.get("datasetId");
        String question = (String) body.get("message");

        if (question == null || question.isBlank()) {
            send(emitter, "error", Map.of("message", "消息不能为空"));
            emitter.complete();
            return emitter;
        }
        if (sessionId == null || sessionId.isBlank()) sessionId = UUID.randomUUID().toString();
        if (datasetId == null || datasetId.isBlank()) {
            send(emitter, "error", Map.of("message", "请先选择数据集"));
            emitter.complete();
            return emitter;
        }

        Dataset dataset = metaRepo.getDataset(datasetId);
        if (dataset == null) {
            send(emitter, "error", Map.of("message", "数据集不存在"));
            emitter.complete();
            return emitter;
        }

        ChatSession session = metaRepo.getSession(sessionId);
        if (session == null) {
            session = new ChatSession();
            session.setId(sessionId);
            session.setTitle(question.length() > 30 ? question.substring(0, 30) + "..." : question);
            session.setDatasetId(datasetId);
            session.setModel((String) body.getOrDefault("model", "deepseek-chat"));
            session.setCreatedAt(LocalDateTime.now());
            session.setUpdatedAt(LocalDateTime.now());
            metaRepo.saveSession(session);
        }

        ChatMessage userMsg = new ChatMessage();
        userMsg.setId(UUID.randomUUID().toString());
        userMsg.setRole("user");
        userMsg.setContent(question);
        userMsg.setCreatedAt(LocalDateTime.now());
        metaRepo.saveMessage(sessionId, userMsg, null);

        final String sid = sessionId;
        final Dataset ds = dataset;
        final String model = session.getModel();
        final String userId = userMsg.getId();

        executor.execute(() -> {
            try {
                send(emitter, "init", Map.of("sessionId", sid, "datasetId", datasetId,
                        "userMessageId", userId, "model", model));

                send(emitter, "status", Map.of("stage", "planning", "message", "正在理解问题并生成分析计划..."));
                AnalysisPlan plan = orchestrator.plan(ds, question);
                send(emitter, "plan", plan);

                send(emitter, "status", Map.of("stage", "executing", "message", "正在执行分析计算..."));
                AnalysisResult result = orchestrator.execute(ds, plan);
                send(emitter, "result", result);
                if (result.getChart() != null) send(emitter, "chart", result.getChart());

                send(emitter, "status", Map.of("stage", "concluding", "message", "正在生成分析结论..."));
                // 捕获每个流式 token 后再写回 result；orchestrator.conclude 内部也会 setConclusion，
                // 但流式场景下 controller 必须用收集到的完整文本覆盖，否则会把结论清空。
                // Capture every streamed token and write back to result; orchestrator.conclude also
                // sets the conclusion, but in streaming mode the controller must overwrite with the
                // fully collected text, otherwise the conclusion would be wiped to empty.
                StringBuilder conclusion = new StringBuilder();
                orchestrator.conclude(ds, question, plan, result, token -> {
                    conclusion.append(token);
                    send(emitter, "text", Map.of("content", token));
                });
                if (conclusion.length() > 0) result.setConclusion(conclusion.toString());

                orchestrator.memorize(ds, question, plan, result);

                ChatMessage assistantMsg = new ChatMessage();
                assistantMsg.setId(UUID.randomUUID().toString());
                assistantMsg.setRole("assistant");
                assistantMsg.setContent(result.getConclusion());
                assistantMsg.setModel(model);
                assistantMsg.setCreatedAt(LocalDateTime.now());
                assistantMsg.setPlan(plan);
                assistantMsg.setResult(result);

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("plan", plan);
                payload.put("result", result);
                metaRepo.saveMessage(sid, assistantMsg, mapper.writeValueAsString(payload));

                send(emitter, "done", Map.of("sessionId", sid, "messageId", assistantMsg.getId()));
                emitter.complete();
                log.info("[Chat] completed session={}", sid);
            } catch (Exception e) {
                log.error("[Chat] failed", e);
                send(emitter, "error", Map.of("message", e.getMessage() != null ? e.getMessage() : "分析失败"));
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> log.warn("[SSE] client disconnected: {}", e.getMessage()));
        return emitter;
    }

    @GetMapping("/sessions")
    public Map<String, Object> sessions() {
        return Map.of("sessions", metaRepo.listSessions());
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> session(@PathVariable String id) {
        ChatSession s = metaRepo.getSession(id);
        return s == null ? ResponseEntity.status(404).body(Map.of("error", "会话不存在"))
                : ResponseEntity.ok(Map.of("session", s));
    }

    @PatchMapping("/sessions/{id}")
    public Map<String, Object> renameSession(@PathVariable String id, @RequestBody Map<String, String> body) {
        if (body.containsKey("title")) metaRepo.updateSessionTitle(id, body.get("title"));
        return Map.of("success", true);
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String, Object> deleteSession(@PathVariable String id) {
        metaRepo.deleteSession(id);
        return Map.of("success", true);
    }

    /**
     * 查询当前 LLM 配置（脱敏）/ Get current LLM config (masked).
     *
     * @return 配置映射 / Configuration map
     */
    @GetMapping("/config/llm")
    public Map<String, Object> getLlmConfig() {
        return llmConfigService.current();
    }

    /**
     * 运行时更新 LLM 配置，立即生效 / Update LLM config at runtime with immediate effect.
     *
     * @param body 配置项 / Configuration items
     * @return 更新后的脱敏配置 / Updated masked configuration
     */
    @PostMapping("/config/llm")
    public Map<String, Object> updateLlmConfig(@RequestBody Map<String, String> body) {
        return llmConfigService.update(body);
    }

    private void send(SseEmitter emitter, String type, Object data) {
        try {
            emitter.send(SseEmitter.event().name(type).data(mapper.writeValueAsString(data)));
        } catch (Exception e) {
            // client disconnected or emitter completed; stop pushing events
            log.debug("[SSE] skip send {}: {}", type, e.getMessage());
        }
    }
}

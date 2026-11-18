package com.dataagent.model;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class ChatMessage {
    private String id;
    private String role; // user | assistant
    private String content;
    private String model;
    private LocalDateTime createdAt;
    private AnalysisPlan plan;
    private AnalysisResult result;
    private Map<String, Object> metadata;
}

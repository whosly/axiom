package com.axiom.model;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class ChatSession {
    private String id;
    private String title;
    private String datasetId;
    private String model = "deepseek-chat";
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<ChatMessage> messages = new ArrayList<>();
}

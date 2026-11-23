package com.axiom.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class AnalysisResult {
    private String type;
    private String title;
    private String conclusion;
    private List<String> insights;
    private ChartSpec chart;
    private List<Map<String, Object>> rows;
    private Map<String, Object> summary;
    private String sql;
}

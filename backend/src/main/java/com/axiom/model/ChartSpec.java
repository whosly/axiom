package com.axiom.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChartSpec {
    private String type;            // line | bar | pie | scatter
    @JsonProperty("xField")
    private String xField;
    @JsonProperty("yFields")
    private List<String> yFields;
    private String title;
    private List<Map<String, Object>> series;
}

package com.axiom.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnInfo {
    /** Sanitized SQL-safe column name */
    private String name;
    /** Original header from the source file */
    private String originalName;
    /** numeric | temporal | categorical | boolean */
    private String type;
    private int nullCount;
    private String sampleValues;
}

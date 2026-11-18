package com.dataagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Dataset {
    private String id;
    private String name;
    private String fileName;
    private String fileType;
    private int rowCount;
    private int columnCount;
    private String tableName;
    private List<ColumnInfo> columns;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

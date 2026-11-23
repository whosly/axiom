# 分析领域 DDD 模型 / Analysis Domain DDD Model

> 本文说明如何用 DDD（领域驱动设计）组织分析领域，以及一次真实缺陷（period 显示）是如何被领域模型修复的。

## 1. 为什么要 DDD / Why DDD

改造前，分析计划 `AnalysisPlan` 的字段都是裸字符串：

```java
private String intent;          // "trend" / "trendd"（拼错也能编译）
private String aggregation;     // "sum" / "SUM" / "avg"
private String timeGranularity; // "month" / "moth"
private String chartType;
```

问题：
- 拼写错误编译期发现不了，运行期静默走默认分支
- `safeAgg()` / `safeGranularity()` 等字符串判断散落各处
- **领域知识（如"按月聚合 period 应显示 2026-01"）无处安放**，只能散在 SQL 拼接里 → 导致了 period 缺陷

改造后：领域概念用枚举值对象表达，行为内聚到枚举方法上。

## 2. 领域模型结构 / Domain Model

```
com.axiom.domain/          领域层（无框架依赖）
├── AnalysisIntent    枚举  分析意图：QUERY/TREND/ANOMALY/COMPARE/SUMMARY
├── Aggregation       枚举  聚合方式：SUM/AVG/COUNT/MIN/MAX（含 getSql()）
├── TimeGranularity   枚举  时间粒度：HOUR/DAY/WEEK/MONTH/QUARTER/YEAR（含 periodSql()）
├── ChartType         枚举  图表类型：LINE/BAR/PIE/SCATTER/TABLE
└── SortOrder         枚举  排序方向：ASC/DESC（含 getSql()）

com.axiom.model/
└── AnalysisPlan      聚合根/值对象  用上述枚举作为字段类型 + 便利方法

com.axiom.service/
└── AnalysisEngine    领域服务  switch(intent) 穷举，调用枚举方法生成 SQL
```

## 3. 枚举即行为 / Enums Carry Behavior

DDD 强调"值对象封装行为"。关键例子是 `TimeGranularity`：

```java
public String periodSql(String timeColumnExpr) {
    return "strftime(DATE_TRUNC('" + truncUnit + "', CAST(" + timeColumnExpr
           + " AS TIMESTAMP)), '" + format + "')";
}
```

每个粒度自带正确的展示格式：

| 粒度 | format | period 示例 |
|---|---|---|
| MONTH | `%Y-%m` | `2026-01` |
| DAY | `%Y-%m-%d` | `2026-01-05` |
| WEEK | `%Y-W%V` | `2026-W02` |
| QUARTER | `%Y-Q%q` | `2026-Q1` |
| YEAR | `%Y` | `2026` |

聚合枚举同样内聚 SQL 关键字：`Aggregation.SUM.getSql()` → `"SUM"`。

## 4. 缺陷修复案例：period 显示 / Defect Fix Case

**现象**：用户问"分析销售额的变化趋势"，period 列显示 `2026-01-01`、`2026-02-01`...
**根因**：`DATE_TRUNC('month', ...)` 返回当月第一天，语义上是时间戳，但展示给用户的是"每月总额"，格式不匹配。
**修复**：用 `TimeGranularity.periodSql()` 包裹 `strftime`，按月显示 `2026-01`。
**防复发**：格式规则内聚在枚举里，所有趋势分析自动受益，不会再散落到 SQL 拼接中。

## 5. JSON 兼容 / JSON Compatibility

枚举通过 Jackson 注解保持对外协议不变：

- `@JsonValue` → 序列化输出小写值（`trend`、`month`），LLM 与前端无需改动
- `@JsonCreator from(String)` → 反序列化同时接受小写值、大写枚举名、别名（`average`→AVG），非法值回退安全默认

因此这是一次**纯内部重构**，对 API 与 LLM prompt 完全兼容。

## 6. 穷举与类型安全 / Exhaustiveness & Type Safety

`AnalysisEngine.execute()` 用 `switch(plan.getIntent())` 穷举所有意图，编译器保证每种意图都有分支：

```java
return switch (intent) {
    case TREND -> trend(...);
    case ANOMALY -> anomaly(...);
    case COMPARE -> compare(...);
    case SUMMARY -> summary(...);
    case QUERY -> query(...);
};
```

新增意图时编译器会提示遗漏，不会再出现"静默走 default"的隐患。

## 7. 复查结论 / Review of Other Analyses

逐一复查了五类分析的时间与聚合逻辑：

| 分析 | 聚合来源 | 时间/维度 | 状态 |
|---|---|---|---|
| 查询 query | `getAggregation().getSql()` | dimension 分组 | ✅ |
| 趋势 trend | `getAggregation().getSql()` | `periodSql()` 格式化 | ✅ 已修 |
| 异常 anomaly | `getAggregation().getSql()` | 可选分组列 | ✅ |
| 对比 compare | `getAggregation().getSql()` | `SortOrder.getSql()` | ✅ |
| 概览 summary | 描述性统计 | — | ✅ |

已知次要项：异常识别的 `item` 由 `CAST(数值 AS VARCHAR)` 产生 `22100.0`（带 `.0`），不影响正确性，后续可优化展示。

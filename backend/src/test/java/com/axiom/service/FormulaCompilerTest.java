package com.axiom.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 公式解析与 SQL 编译测试 / Tests for formula parsing and SQL compilation.
 *
 * <p>覆盖：四则运算优先级、括号嵌套、常数、中文列名、除零 NULLIF 防护、
 * 公式引用公式（含循环检测）、未知字段、语法错误。
 * Covers: operator precedence, nesting, constants, Chinese column names, the
 * NULLIF zero-division guard, nested formula references (incl. cycles), unknown
 * fields and syntax errors.
 */
class FormulaCompilerTest {

    /** 无公式引用的查找器 / Lookup without nested formulas. */
    private static final Map<String, String> NO_REFS = Map.of();

    /** 全部字段存在 / All columns exist. */
    private static final java.util.function.Predicate<String> ANY = n -> true;

    @Test
    @DisplayName("加减：字段展开为聚合列")
    void simpleSum() {
        String sql = FormulaCompiler.compile("销售额 - 退款额", NO_REFS::get, ANY, "SUM");
        assertThat(sql).isEqualTo("(SUM(\"销售额\") - SUM(\"退款额\"))");
    }

    @Test
    @DisplayName("乘除优先级高于加减")
    void precedence() {
        String sql = FormulaCompiler.compile("销售额 - 成本 * 2 / 数量", NO_REFS::get, ANY, "SUM");
        // 乘除先组合；除法带 NULLIF 防护 / Multiplicative group first; division is NULLIF-guarded.
        assertThat(sql).isEqualTo("(SUM(\"销售额\") - ((SUM(\"成本\") * 2) / NULLIF(SUM(\"数量\"), 0)))");
    }

    @Test
    @DisplayName("括号嵌套改变结合顺序")
    void parentheses() {
        String sql = FormulaCompiler.compile("(销售额 - 成本) / 销售额 * 100", NO_REFS::get, ANY, "SUM");
        assertThat(sql).isEqualTo(
                "(((SUM(\"销售额\") - SUM(\"成本\")) / NULLIF(SUM(\"销售额\"), 0)) * 100)");
    }

    @Test
    @DisplayName("常数与一元负号")
    void constants() {
        assertThat(FormulaCompiler.compile("销售额 * 1.08 + 3", NO_REFS::get, ANY, "SUM"))
                .isEqualTo("((SUM(\"销售额\") * 1.08) + 3)");
        assertThat(FormulaCompiler.compile("-成本 + 10", NO_REFS::get, ANY, "SUM"))
                .isEqualTo("((0 - SUM(\"成本\")) + 10)");
    }

    @Test
    @DisplayName("行级公式：aggSql 为 null 时叶节点不聚合")
    void rowLevelFormula() {
        String sql = FormulaCompiler.compile("销售额 - 成本", NO_REFS::get, ANY, null);
        assertThat(sql).isEqualTo("(\"销售额\" - \"成本\")");
    }

    @Test
    @DisplayName("公式引用公式：递归展开")
    void nestedFormulaReference() {
        Map<String, String> formulas = Map.of(
                "毛利", "销售额 - 成本",
                "毛利率", "(毛利 / 销售额) * 100");
        String sql = FormulaCompiler.compile("(毛利率 / 销售额) * 100",
                formulas::get, ANY, "SUM");
        // 毛利率 → (毛利/销售额)*100 → ((SUM(销售额)-SUM(成本))/NULLIF(SUM(销售额),0))*100
        assertThat(sql).contains("SUM(\"成本\")");
        assertThat(sql).contains("NULLIF(SUM(\"销售额\"), 0)");
        assertThat(sql).startsWith("(((");
    }

    @Test
    @DisplayName("循环引用必须报错")
    void circularReferenceFails() {
        Map<String, String> formulas = Map.of(
                "A", "B + 1",
                "B", "A * 2");
        assertThatThrownBy(() -> FormulaCompiler.compile("A", formulas::get, ANY, "SUM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("循环引用");
    }

    @Test
    @DisplayName("自引用必须报错")
    void selfReferenceFails() {
        Map<String, String> formulas = Map.of("GMV", "GMV + 1");
        assertThatThrownBy(() -> FormulaCompiler.compile("GMV", formulas::get, ANY, "SUM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("循环引用");
    }

    @Test
    @DisplayName("未知字段必须报错")
    void unknownFieldFails() {
        assertThatThrownBy(() -> FormulaCompiler.compile("幻觉字段 * 2", NO_REFS::get, n -> false, "SUM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("幻觉字段");
    }

    @Test
    @DisplayName("语法错误：悬空运算符 / 不匹配括号 / 多余输入")
    void syntaxErrors() {
        assertThatThrownBy(() -> FormulaCompiler.validateSyntax("销售额 +"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormulaCompiler.validateSyntax("(销售额"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("括号不匹配");
        assertThatThrownBy(() -> FormulaCompiler.validateSyntax("销售额 退货额"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多余内容");
        assertThatThrownBy(() -> FormulaCompiler.validateSyntax(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

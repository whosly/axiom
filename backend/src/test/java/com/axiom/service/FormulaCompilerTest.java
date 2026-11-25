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

    @Test
    @DisplayName("意外运算符必须报语法错误而非误报未知字段")
    void unexpectedOperatorTokens() {
        // 此前 "*"/")" 兜底被当作字段引用，报"未知字段「*」"的误导性错误
        // / Previously "*"/")" fell through to FieldRef and reported a misleading unknown-column error.
        assertThatThrownBy(() -> FormulaCompiler.validateSyntax("销售额 * * 成本"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("意外的运算符");
        assertThatThrownBy(() -> FormulaCompiler.validateSyntax("销售额 + )"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("意外的运算符");
        assertThatThrownBy(() -> FormulaCompiler.validateSyntax("()"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("意外的运算符");
        assertThatThrownBy(() -> FormulaCompiler.validateSyntax("销售额 / / 成本"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("意外的运算符");
    }

    @Test
    @DisplayName("括号嵌套超过上限必须报错而非栈溢出")
    void deepParenNestingFailsGracefully() {
        // 65 层括号：解析期上限 64 层，报 IllegalArgumentException 而不是 StackOverflowError
        // / 65 paren levels: parse-time cap rejects with IllegalArgumentException, not StackOverflowError.
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 65; i++) deep.append('(');
        deep.append("销售额");
        for (int i = 0; i < 65; i++) deep.append(')');
        assertThatThrownBy(() -> FormulaCompiler.validateSyntax(deep.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("括号嵌套过深");
    }

    @Test
    @DisplayName("合法的左结合长链不受深度限制")
    void longFlatChainCompiles() {
        // 100 项加总是合法财务公式，深度只随公式引用展开增长，不应误判"嵌套过深"
        // / A 100-term sum is legitimate; depth counts formula expansion, not operators.
        StringBuilder flat = new StringBuilder("销售额");
        for (int i = 0; i < 99; i++) flat.append(" + 销售额");
        String sql = FormulaCompiler.compile(flat.toString(), NO_REFS::get, ANY, "SUM");
        assertThat(sql).startsWith("(");
        assertThat(sql).contains("SUM(\"销售额\")");
        // 100 个叶子全部展开 / All 100 leaves expanded.
        assertThat(sql.split("SUM\\(\"销售额\"\\)", -1).length - 1).isEqualTo(100);
    }

    @Test
    @DisplayName("公式引用链超过上限报错")
    void formulaChainCap() {
        Map<String, String> formulas = new java.util.HashMap<>();
        formulas.put("F0", "销售额 + 1");
        for (int i = 1; i <= 40; i++) formulas.put("F" + i, "F" + (i - 1) + " + 1");
        assertThatThrownBy(() -> FormulaCompiler.compile("F40", formulas::get, ANY, "SUM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("引用链过长");
    }

    @Test
    @DisplayName("超出 long 范围的常数不发生截断饱和")
    void hugeConstantsKeepPrecision() {
        // 此前 (long) 1e20 饱和为 Long.MAX_VALUE，静默产出错误数字
        // / Previously (long) 1e20 saturated to Long.MAX_VALUE, silently wrong.
        String sql = FormulaCompiler.compile("销售额 * 100000000000000000000", NO_REFS::get, ANY, "SUM");
        assertThat(sql).contains("1.0E20");
    }

    /**
     * 一元符号链计入深度上限：此前只有括号计数，超长 "- - - …" 链无限递归
     * 打爆栈。/ Unary chains share the depth cap — previously unbounded recursion.
     */
    @Test
    @DisplayName("一元符号链超限显式报错而非栈溢出")
    void unaryChainCap() {
        String longChain = "- ".repeat(200) + "销售额";
        assertThatThrownBy(() -> FormulaCompiler.compile(longChain, NO_REFS::get, ANY, "SUM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("嵌套过深");
        // 短链正常编译 / short chains still compile.
        assertThat(FormulaCompiler.compile("- - 销售额", NO_REFS::get, ANY, "SUM"))
                .contains("销售额");
    }
}

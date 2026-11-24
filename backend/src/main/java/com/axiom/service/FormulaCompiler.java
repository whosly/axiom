package com.axiom.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 指标公式解析与编译 / Metric formula parser & SQL compiler.
 *
 * <p>语法（递归下降，支持任意括号嵌套）/ Grammar (recursive descent, arbitrary nesting):
 * <pre>
 * expr   := term (('+' | '-') term)*
 * term   := factor (('*' | '/') factor)*
 * factor := NUMBER | FIELD | '(' expr ')' | '-' factor
 * </pre>
 *
 * <p>编译语义为 aggregate-then-compute：字段叶节点展开为 {@code AGG(列)}，运算结构
 * 保持不变——比率公式（如 (收入-成本)/收入）在聚合后计算，与财务口径一致。除法右侧
 * 编译为 {@code NULLIF(expr, 0)}，分母为 0 得 NULL 而非 Infinity。公式可引用其他公式
 * （按标题递归展开，循环检测 + 深度限制）；未命中的操作数必须是数据集列。
 * / Aggregate-then-compute semantics: field leaves expand to {@code AGG(col)} so ratio
 * formulas compute after aggregation (financially correct). Division is guarded by
 * NULLIF. Formulas may reference other formulas by title (cycle-checked); non-formula
 * operands must be dataset columns.
 */
public final class FormulaCompiler {

    private static final int MAX_FORMULA_DEPTH = 5;
    private static final int MAX_EMIT_DEPTH = 32;

    private FormulaCompiler() {
    }

    /** AST 节点 / AST node. */
    sealed interface Node permits Num, FieldRef, Op {
    }

    /** 数字常数 / Numeric constant. */
    record Num(double value) implements Node {
    }

    /** 字段或公式引用 / Column or nested-formula reference. */
    record FieldRef(String name) implements Node {
    }

    /** 二元运算 / Binary operation. */
    record Op(char op, Node left, Node right) implements Node {
    }

    /**
     * 仅做语法校验（录入期使用，不展开引用）/ Syntax-only validation for ingestion.
     *
     * @param expression 公式表达式 / Formula expression.
     * @throws IllegalArgumentException 语法错误 / On syntax errors.
     */
    public static void validateSyntax(String expression) {
        parse(expression);
    }

    /**
     * 编译公式为 SQL 表达式 / Compile a formula into a SQL expression.
     *
     * @param expression    纯表达式（不含「名称 =」前缀）/ Pure expression.
     * @param formulaLookup 标题 → 公式文本 / title-to-formula lookup.
     * @param columnExists  数据集列存在性判断 / Dataset column existence check.
     * @param aggSql        聚合函数关键字（SUM/AVG/...）/ Aggregate SQL keyword.
     * @return SQL 表达式 / Compiled SQL expression.
     * @throws IllegalArgumentException 语法错误 / 未知字段 / 循环引用 / 嵌套过深.
     */
    public static String compile(String expression,
                                 Function<String, String> formulaLookup,
                                 Predicate<String> columnExists,
                                 String aggSql) {
        Node ast = parse(expression);
        StringBuilder sql = new StringBuilder();
        emit(ast, sql, new ArrayDeque<>(), formulaLookup, columnExists, aggSql, 0);
        return sql.toString();
    }

    // ---- parsing ----

    private static Node parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("公式不能为空 / Formula is empty");
        }
        Deque<Token> tokens = tokenize(expression);
        ParserState st = new ParserState(tokens);
        Node node = parseExpr(st);
        if (st.hasNext()) {
            throw fail(expression, st.peek().pos(), "存在无法解析的多余内容 / trailing input");
        }
        return node;
    }

    private static IllegalArgumentException fail(String expr, int pos, String why) {
        return new IllegalArgumentException("公式语法错误（位置 " + pos + "）：" + why
                + " / Formula syntax error at " + pos + ": " + why);
    }

    /** 递归下降状态 / Recursive-descent parser state. */
    private static final class ParserState {
        private final Deque<Token> tokens;

        ParserState(Deque<Token> tokens) {
            this.tokens = tokens;
        }

        boolean hasNext() {
            return !tokens.isEmpty();
        }

        Token peek() {
            return tokens.peek();
        }

        Token next() {
            return tokens.pop();
        }
    }

    private static Node parseExpr(ParserState st) {
        Node left = parseTerm(st);
        while (st.hasNext() && st.peek().isOp('+', '-')) {
            char op = st.next().op();
            left = new Op(op, left, parseTerm(st));
        }
        return left;
    }

    private static Node parseTerm(ParserState st) {
        Node left = parseFactor(st);
        while (st.hasNext() && st.peek().isOp('*', '/')) {
            char op = st.next().op();
            left = new Op(op, left, parseFactor(st));
        }
        return left;
    }

    private static Node parseFactor(ParserState st) {
        if (!st.hasNext()) {
            throw new IllegalArgumentException("公式不完整 / Formula ends unexpectedly");
        }
        Token t = st.next();
        if (t.isOp('(')) {
            Node inner = parseExpr(st);
            if (!st.hasNext() || !st.peek().isOp(')')) {
                throw new IllegalArgumentException("括号不匹配 / Unbalanced parenthesis");
            }
            st.next();
            return inner;
        }
        if (t.isOp('-')) {
            return new Op('-', new Num(0), parseFactor(st));
        }
        if (t.isOp('+')) {
            return parseFactor(st);
        }
        if (t.number() != null) return new Num(t.number());
        return new FieldRef(t.text());
    }

    // ---- tokens ----

    private record Token(String text, Character op, Double number, int pos) {
        boolean isOp(char... ops) {
            if (op == null) return false;
            for (char c : ops) {
                if (op == c) return true;
            }
            return false;
        }
    }

    private static Deque<Token> tokenize(String expr) {
        Deque<Token> out = new ArrayDeque<>();
        int i = 0;
        int n = expr.length();
        while (i < n) {
            char ch = expr.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch == '(' || ch == ')' || ch == '+' || ch == '-' || ch == '*' || ch == '/') {
                out.add(new Token(String.valueOf(ch), ch, null, i));
                i++;
                continue;
            }
            if (Character.isDigit(ch) || ch == '.') {
                int start = i;
                while (i < n && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.')) i++;
                String num = expr.substring(start, i);
                try {
                    out.add(new Token(num, null, Double.parseDouble(num), start));
                } catch (NumberFormatException e) {
                    throw fail(expr, start, "非法数字 / invalid number: " + num);
                }
                continue;
            }
            // 字段名：运算符/括号/空白之外的连续文本（中文列名天然成词）
            // / Field: any run of text that is not an operator, paren or space.
            int start = i;
            while (i < n) {
                char c = expr.charAt(i);
                if (Character.isWhitespace(c) || c == '(' || c == ')'
                        || c == '+' || c == '-' || c == '*' || c == '/') {
                    break;
                }
                i++;
            }
            out.add(new Token(expr.substring(start, i), null, null, start));
        }
        return out;
    }

    // ---- SQL emission ----

    private static void emit(Node node, StringBuilder sql, Deque<String> expanding,
                             Function<String, String> formulaLookup,
                             Predicate<String> columnExists, String aggSql, int depth) {
        if (depth > MAX_EMIT_DEPTH) {
            throw new IllegalArgumentException("公式嵌套过深 / Formula nesting too deep");
        }
        // Java 17 无 record pattern，用 instanceof + record 访问器 / Java 17 lacks record
        // patterns; use instanceof with record accessors.
        if (node instanceof Num n) {
            sql.append(formatNumber(n.value()));
        } else if (node instanceof FieldRef f) {
            emitRef(f.name(), sql, expanding, formulaLookup, columnExists, aggSql, depth);
        } else if (node instanceof Op o) {
            sql.append('(');
            emit(o.left(), sql, expanding, formulaLookup, columnExists, aggSql, depth + 1);
            if (o.op() == '/') {
                // 除零防护：右侧包 NULLIF / Zero-denominator guard via NULLIF.
                sql.append(" / NULLIF(");
                emit(o.right(), sql, expanding, formulaLookup, columnExists, aggSql, depth + 1);
                sql.append(", 0))");
            } else {
                sql.append(' ').append(o.op()).append(' ');
                emit(o.right(), sql, expanding, formulaLookup, columnExists, aggSql, depth + 1);
                sql.append(')');
            }
        }
    }

    /**
     * 操作数落地：公式引用递归展开，否则聚合数据集列 / Resolve an operand: expand a
     * nested formula reference or aggregate a dataset column.
     */
    private static void emitRef(String name, StringBuilder sql, Deque<String> expanding,
                                Function<String, String> formulaLookup,
                                Predicate<String> columnExists, String aggSql, int depth) {
        String referenced = formulaLookup.apply(name);
        if (referenced != null) {
            if (expanding.contains(name)) {
                throw new IllegalArgumentException("公式循环引用 / Circular formula reference: " + name);
            }
            expanding.push(name);
            Node ast = parse(referenced);
            emit(ast, sql, expanding, formulaLookup, columnExists, aggSql, depth + 1);
            expanding.pop();
            return;
        }
        if (!columnExists.test(name)) {
            throw new IllegalArgumentException(
                    "公式引用的字段「" + name + "」不在数据集中 / Unknown column in formula: " + name);
        }
        // aggSql 为 null 表示行级公式（逐行计算，不聚合），叶节点直接输出列引用。
        // / A null aggSql means a row-level formula: leaves emit the bare column.
        if (aggSql == null) {
            sql.append(quote(name));
            return;
        }
        sql.append(aggSql).append('(').append(quote(name)).append(')');
    }

    /** SQL 标识符加引号 / Quote a SQL identifier. */
    private static String quote(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** 数字常量格式化（整数不带小数点）/ Format a numeric constant. */
    private static String formatNumber(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }
}

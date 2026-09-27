package com.mqspg.transform.expr;

import com.fasterxml.jackson.databind.JsonNode;
import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.common.persistence.JsonNodes;
import com.mqspg.transform.ExpressionEvaluator;
import com.mqspg.transform.JsonPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 自研的受限表达式求值器（递归下降）。
 *
 * <p><b>为什么不用 Aviator</b>：tech-design 附录 C-02 指出 Aviator 为 LGPL，
 * 法务尚未确认。而映射表达式实际需要的只是四则运算、比较、逻辑与少量字符串函数，
 * 自研实现的成本远低于引入一个许可证待定的依赖。
 *
 * <p>支持：
 * <ul>
 *   <li>字面量：数字、单/双引号字符串、{@code true}/{@code false}/{@code null}</li>
 *   <li>字段引用：{@code amount}、{@code user.id}、{@code $.amount}（JSONPath）</li>
 *   <li>算术：{@code + - * / %}（{@code +} 遇字符串为拼接，与 JS 一致）</li>
 *   <li>比较：{@code == != < <= > >=}；逻辑：{@code && || !}；三元：{@code ? :}</li>
 *   <li>函数：{@code upper lower trim length concat coalesce abs round floor ceil
 *       int long decimal string now}</li>
 * </ul>
 *
 * <p><b>注意</b>：这不是通用脚本引擎，不提供属性访问、索引赋值或副作用，
 * 因此不存在注入风险 —— 表达式来自配置库，但仍按不可信输入处理。
 */
@Component
@RequiredArgsConstructor
public class SimpleExpressionEvaluator implements ExpressionEvaluator {

    private final JsonPaths jsonPaths;

    @Override
    public Object evaluate(String expression, JsonNode context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            Parser parser = new Parser(tokenize(expression), context, jsonPaths);
            Object value = parser.parseExpression();
            parser.expectEof();
            return value;
        } catch (ProcessingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProcessingException(ErrorCode.EXPRESSION_ERROR,
                    "表达式求值失败: %s —— %s".formatted(expression, e.getMessage()), e);
        }
    }

    // ==================================================================
    // 词法
    // ==================================================================

    enum Kind { NUM, STR, IDENT, PATH, OP, LP, RP, COMMA, EOF }

    record Token(Kind kind, String text) {
    }

    private static final String SINGLE_CHAR_OPS = "+-*/%<>!?:";
    private static final String DELIMITERS = ",+-*/%<>!=&|?:()";

    static List<Token> tokenize(String src) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = src.length();

        while (i < n) {
            char c = src.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            // 数字：1、1.5、.5
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                int start = i;
                while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) {
                    i++;
                }
                out.add(new Token(Kind.NUM, src.substring(start, i)));
                continue;
            }

            // 字符串字面量
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < n && src.charAt(i) != quote) {
                    char ch = src.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        char escaped = src.charAt(i + 1);
                        sb.append(switch (escaped) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case 'r' -> '\r';
                            default -> escaped;
                        });
                        i += 2;
                    } else {
                        sb.append(ch);
                        i++;
                    }
                }
                if (i >= n) {
                    throw new ProcessingException(ErrorCode.EXPRESSION_ERROR, "字符串字面量未闭合");
                }
                i++; // 跳过收尾引号
                out.add(new Token(Kind.STR, sb.toString()));
                continue;
            }

            // JSONPath：$ 开头，方括号内允许任意字符（含 - 与 ?）
            if (c == '$') {
                int start = i++;
                int depth = 0;
                while (i < n) {
                    char ch = src.charAt(i);
                    if (ch == '[') {
                        depth++;
                        i++;
                        continue;
                    }
                    if (ch == ']') {
                        depth--;
                        i++;
                        continue;
                    }
                    if (depth == 0 && (Character.isWhitespace(ch) || DELIMITERS.indexOf(ch) >= 0)) {
                        break;
                    }
                    i++;
                }
                out.add(new Token(Kind.PATH, src.substring(start, i)));
                continue;
            }

            // 标识符 / 点分字段名
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i))
                        || src.charAt(i) == '_' || src.charAt(i) == '.')) {
                    i++;
                }
                out.add(new Token(Kind.IDENT, src.substring(start, i)));
                continue;
            }

            if (c == '(') {
                out.add(new Token(Kind.LP, "("));
                i++;
                continue;
            }
            if (c == ')') {
                out.add(new Token(Kind.RP, ")"));
                i++;
                continue;
            }
            if (c == ',') {
                out.add(new Token(Kind.COMMA, ","));
                i++;
                continue;
            }

            // 双字符运算符优先
            if (i + 1 < n) {
                String two = src.substring(i, i + 2);
                if (two.equals("==") || two.equals("!=") || two.equals("<=")
                        || two.equals(">=") || two.equals("&&") || two.equals("||")) {
                    out.add(new Token(Kind.OP, two));
                    i += 2;
                    continue;
                }
            }
            if (SINGLE_CHAR_OPS.indexOf(c) >= 0) {
                out.add(new Token(Kind.OP, String.valueOf(c)));
                i++;
                continue;
            }

            throw new ProcessingException(ErrorCode.EXPRESSION_ERROR,
                    "表达式中出现无法识别的字符 '%c'（位置 %d）".formatted(c, i));
        }

        out.add(new Token(Kind.EOF, ""));
        return out;
    }

    // ==================================================================
    // 语法分析 + 求值（边解析边求值，无需构造 AST）
    // ==================================================================

    static final class Parser {

        private final List<Token> tokens;
        private final JsonNode ctx;
        private final JsonPaths jsonPaths;
        private int pos;

        Parser(List<Token> tokens, JsonNode ctx, JsonPaths jsonPaths) {
            this.tokens = tokens;
            this.ctx = ctx;
            this.jsonPaths = jsonPaths;
        }

        Object parseExpression() {
            return parseTernary();
        }

        void expectEof() {
            if (peek().kind() != Kind.EOF) {
                throw syntax("表达式末尾有多余内容 '" + peek().text() + "'");
            }
        }

        // ---- 优先级由低到高 ----

        private Object parseTernary() {
            Object cond = parseOr();
            if (peekOp("?")) {
                next();
                Object whenTrue = parseExpression();
                expectOp(":");
                Object whenFalse = parseExpression();
                return truthy(cond) ? whenTrue : whenFalse;
            }
            return cond;
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (peekOp("||")) {
                next();
                Object right = parseAnd();
                left = truthy(left) || truthy(right);
            }
            return left;
        }

        private Object parseAnd() {
            Object left = parseEquality();
            while (peekOp("&&")) {
                next();
                Object right = parseEquality();
                left = truthy(left) && truthy(right);
            }
            return left;
        }

        private Object parseEquality() {
            Object left = parseRelational();
            while (true) {
                if (peekOp("==")) {
                    next();
                    left = equal(left, parseRelational());
                } else if (peekOp("!=")) {
                    next();
                    left = !equal(left, parseRelational());
                } else {
                    return left;
                }
            }
        }

        private Object parseRelational() {
            Object left = parseAdditive();
            while (true) {
                String op = peekOpAny("<=", ">=", "<", ">");
                if (op == null) {
                    return left;
                }
                next();
                left = compare(left, parseAdditive(), op);
            }
        }

        private Object parseAdditive() {
            Object left = parseMultiplicative();
            while (true) {
                if (peekOp("+")) {
                    next();
                    left = add(left, parseMultiplicative());
                } else if (peekOp("-")) {
                    next();
                    left = subtract(left, parseMultiplicative());
                } else {
                    return left;
                }
            }
        }

        private Object parseMultiplicative() {
            Object left = parseUnary();
            while (true) {
                if (peekOp("*")) {
                    next();
                    left = arithmetic(left, parseUnary(), '*');
                } else if (peekOp("/")) {
                    next();
                    left = arithmetic(left, parseUnary(), '/');
                } else if (peekOp("%")) {
                    next();
                    left = arithmetic(left, parseUnary(), '%');
                } else {
                    return left;
                }
            }
        }

        private Object parseUnary() {
            if (peekOp("-")) {
                next();
                return numeric(parseUnary()).negate();
            }
            if (peekOp("!")) {
                next();
                return !truthy(parseUnary());
            }
            return parsePrimary();
        }

        private Object parsePrimary() {
            Token t = peek();
            switch (t.kind()) {
                case NUM -> {
                    next();
                    return new BigDecimal(t.text());
                }
                case STR -> {
                    next();
                    return t.text();
                }
                case PATH -> {
                    next();
                    return JsonNodes.unwrap(jsonPaths.read(ctx, t.text()));
                }
                case IDENT -> {
                    next();
                    if (peek().kind() == Kind.LP) {
                        return callFunction(t.text(), parseArgs());
                    }
                    return resolveField(t.text());
                }
                case LP -> {
                    next();
                    Object v = parseExpression();
                    expect(Kind.RP, ")");
                    return v;
                }
                default -> throw syntax("意外的记号 '" + t.text() + "'");
            }
        }

        private List<Object> parseArgs() {
            expect(Kind.LP, "(");
            List<Object> args = new ArrayList<>();
            if (peek().kind() == Kind.RP) {
                next();
                return args;
            }
            args.add(parseExpression());
            while (peek().kind() == Kind.COMMA) {
                next();
                args.add(parseExpression());
            }
            expect(Kind.RP, ")");
            return args;
        }

        // ---- 取值 ----

        private Object resolveField(String name) {
            if (name.equalsIgnoreCase("true")) {
                return Boolean.TRUE;
            }
            if (name.equalsIgnoreCase("false")) {
                return Boolean.FALSE;
            }
            if (name.equalsIgnoreCase("null")) {
                return null;
            }
            if (ctx == null) {
                return null;
            }
            // 先按扁平字段取，再按 JSONPath 取（支持 a.b 嵌套）
            JsonNode direct = ctx.get(name);
            if (direct != null) {
                return JsonNodes.unwrap(direct);
            }
            return JsonNodes.unwrap(jsonPaths.read(ctx, "$." + name));
        }

        private Object callFunction(String name, List<Object> args) {
            return switch (name.toLowerCase()) {
                case "upper" -> text(arg(args, 0)).toUpperCase();
                case "lower" -> text(arg(args, 0)).toLowerCase();
                case "trim" -> text(arg(args, 0)).trim();
                case "length" -> BigDecimal.valueOf(text(arg(args, 0)).length());
                case "concat" -> {
                    StringBuilder sb = new StringBuilder();
                    for (Object a : args) {
                        sb.append(a == null ? "" : text(a));
                    }
                    yield sb.toString();
                }
                case "coalesce" -> {
                    Object found = null;
                    for (Object a : args) {
                        if (a != null) {
                            found = a;
                            break;
                        }
                    }
                    yield found;
                }
                case "abs" -> numeric(arg(args, 0)).abs();
                case "round" -> args.size() > 1
                        ? numeric(arg(args, 0)).setScale(intArg(args, 1), RoundingMode.HALF_UP)
                        : numeric(arg(args, 0)).setScale(0, RoundingMode.HALF_UP);
                case "floor" -> numeric(arg(args, 0)).setScale(0, RoundingMode.FLOOR);
                case "ceil" -> numeric(arg(args, 0)).setScale(0, RoundingMode.CEILING);
                case "int", "long" -> numeric(arg(args, 0)).longValueExact();
                case "decimal" -> numeric(arg(args, 0));
                case "string", "str" -> text(arg(args, 0));
                case "now" -> OffsetDateTime.now(ZoneOffset.UTC);
                default -> throw new ProcessingException(ErrorCode.EXPRESSION_ERROR, "未知的表达式函数: " + name);
            };
        }

        // ---- 运算语义 ----

        /** {@code +}：双方均可数值化则相加，否则字符串拼接（与 JS 一致）。 */
        private static Object add(Object a, Object b) {
            if (a == null && b == null) {
                return null;
            }
            if (isNumeric(a) && isNumeric(b)) {
                return numeric(a).add(numeric(b));
            }
            return text(a) + text(b);
        }

        private static Object subtract(Object a, Object b) {
            return numeric(a).subtract(numeric(b));
        }

        private static Object arithmetic(Object a, Object b, char op) {
            BigDecimal x = numeric(a);
            BigDecimal y = numeric(b);
            return switch (op) {
                case '*' -> x.multiply(y);
                case '/' -> x.divide(y, 18, RoundingMode.HALF_UP).stripTrailingZeros();
                case '%' -> x.remainder(y);
                default -> throw new IllegalStateException("unreachable");
            };
        }

        private static Object compare(Object a, Object b, String op) {
            int cmp;
            if (isNumeric(a) && isNumeric(b)) {
                cmp = numeric(a).compareTo(numeric(b));
            } else if (a == null || b == null) {
                // null 参与比较时视为最小，避免表达式整体失败
                cmp = (a == null && b == null) ? 0 : (a == null ? -1 : 1);
            } else {
                cmp = text(a).compareTo(text(b));
            }
            return switch (op) {
                case "<" -> cmp < 0;
                case "<=" -> cmp <= 0;
                case ">" -> cmp > 0;
                case ">=" -> cmp >= 0;
                default -> throw new IllegalStateException("unreachable");
            };
        }

        private static boolean equal(Object a, Object b) {
            if (isNumeric(a) && isNumeric(b)) {
                return numeric(a).compareTo(numeric(b)) == 0;
            }
            return Objects.equals(a, b);
        }

        private static boolean truthy(Object v) {
            if (v == null) {
                return false;
            }
            if (v instanceof Boolean b) {
                return b;
            }
            if (v instanceof Number || v instanceof BigDecimal) {
                return numeric(v).compareTo(BigDecimal.ZERO) != 0;
            }
            String s = v.toString();
            return !s.isEmpty() && !s.equalsIgnoreCase("false");
        }

        private static boolean isNumeric(Object v) {
            return v instanceof Number || v instanceof BigDecimal || v instanceof Boolean;
        }

        private static BigDecimal numeric(Object v) {
            if (v == null) {
                return BigDecimal.ZERO;
            }
            if (v instanceof BigDecimal bd) {
                return bd;
            }
            if (v instanceof Boolean b) {
                return b ? BigDecimal.ONE : BigDecimal.ZERO;
            }
            if (v instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            String s = v.toString().trim();
            if (s.isEmpty()) {
                return BigDecimal.ZERO;
            }
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                throw new ProcessingException(ErrorCode.EXPRESSION_ERROR, "无法作为数值参与运算: " + s);
            }
        }

        private static String text(Object v) {
            return v == null ? "" : v.toString();
        }

        private static Object arg(List<Object> args, int index) {
            return index < args.size() ? args.get(index) : null;
        }

        private static int intArg(List<Object> args, int index) {
            return numeric(arg(args, index)).intValueExact();
        }

        // ---- 记号访问 ----

        private Token peek() {
            return tokens.get(pos);
        }

        private void next() {
            if (pos < tokens.size() - 1) {
                pos++;
            }
        }

        private boolean peekOp(String op) {
            Token t = peek();
            return t.kind() == Kind.OP && t.text().equals(op);
        }

        private String peekOpAny(String... ops) {
            Token t = peek();
            if (t.kind() != Kind.OP) {
                return null;
            }
            for (String op : ops) {
                if (t.text().equals(op)) {
                    return op;
                }
            }
            return null;
        }

        private void expectOp(String op) {
            if (!peekOp(op)) {
                throw syntax("期望 '" + op + "'，实际为 '" + peek().text() + "'");
            }
            next();
        }

        private void expect(Kind kind, String display) {
            if (peek().kind() != kind) {
                throw syntax("期望 '" + display + "'，实际为 '" + peek().text() + "'");
            }
            next();
        }

        private ProcessingException syntax(String message) {
            return new ProcessingException(ErrorCode.EXPRESSION_ERROR, "表达式语法错误: " + message);
        }
    }
}

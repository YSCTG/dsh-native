package com.dshnative;

import org.json.JSONArray;

/**
 * 极小的公式求值器：只为了给生成的 xlsx 写入**缓存值**（`<v>`）。
 *
 * 为什么需要：xlsx 里的公式格长这样 `<c><f>SUM(B2:B4)</f><v></v></c>`。
 * 没有 `<v>` 时，Excel 打开会自己算，但**只读缓存的查看器（比如我们预览用的 SheetJS）就显示空白**。
 * 所以生成时顺手算一遍写进去。
 *
 * 明确的能力边界：只支持 SUM / AVERAGE / MIN / MAX / COUNT 与 + - * / 括号、单元格引用、区域引用。
 * 算不出来（不认识的函数、除零、循环引用）就返回 null —— 宁可不写缓存值，也不写错的。
 */
final class Formulas {

    private Formulas() {}

    /** 求值；算不出来返回 null。depth 用来兜住循环引用。 */
    static Double eval(String expr, JSONArray rows) {
        return eval(expr, rows, 0);
    }

    static Double eval(String expr, JSONArray rows, int depth) {
        if (expr == null || rows == null || depth > 12) return null;
        try {
            Parser p = new Parser(expr.trim(), rows, depth);
            Double v = p.parseExpr();
            if (v == null || p.pos < p.src.length()) return null;   // 有多余字符就别信
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------

    private static final class Parser {
        final String src;
        final JSONArray rows;
        final int depth;
        int pos;

        Parser(String src, JSONArray rows, int depth) {
            this.src = src;
            this.rows = rows;
            this.depth = depth;
        }

        private void skip() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        private char peek() {
            skip();
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        Double parseExpr() {
            Double left = parseTerm();
            while (left != null) {
                char op = peek();
                if (op != '+' && op != '-') break;
                pos++;
                Double right = parseTerm();
                if (right == null) return null;
                left = op == '+' ? left + right : left - right;
            }
            return left;
        }

        Double parseTerm() {
            Double left = parseFactor();
            while (left != null) {
                char op = peek();
                if (op != '*' && op != '/') break;
                pos++;
                Double right = parseFactor();
                if (right == null) return null;
                if (op == '/' && right == 0.0) return null;
                left = op == '*' ? left * right : left / right;
            }
            return left;
        }

        Double parseFactor() {
            char ch = peek();
            if (ch == '-') { pos++; Double v = parseFactor(); return v == null ? null : -v; }
            if (ch == '+') { pos++; return parseFactor(); }
            if (ch == '(') {
                pos++;
                Double v = parseExpr();
                if (v == null) return null;
                if (peek() != ')') return null;
                pos++;
                return v;
            }
            if (Character.isDigit(ch) || ch == '.') return parseNumber();
            if (Character.isLetter(ch)) return parseNameOrCall();
            return null;
        }

        Double parseNumber() {
            int st = pos;
            while (pos < src.length()
                    && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
            try {
                return Double.parseDouble(src.substring(st, pos));
            } catch (Throwable t) {
                return null;
            }
        }

        /** A1 引用，或者 SUM(...) 这样的函数调用。 */
        Double parseNameOrCall() {
            int st = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)))) pos++;
            String word = src.substring(st, pos);
            if (peek() == '(') return parseCall(word.toUpperCase());
            return cellValue(word);
        }

        Double parseCall(String fn) {
            pos++;                                  // '('
            java.util.List<Double> nums = new java.util.ArrayList<>();
            while (true) {
                Double v = parseArgInto(nums);
                if (v == null && nums.isEmpty()) return null;
                if (peek() == ',') { pos++; continue; }
                break;
            }
            if (peek() != ')') return null;
            pos++;
            if (nums.isEmpty()) return null;
            double sum = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (double d : nums) {
                sum += d;
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
            switch (fn) {
                case "SUM": return sum;
                case "AVERAGE": case "AVG": return sum / nums.size();
                case "MIN": return min;
                case "MAX": return max;
                case "COUNT": return (double) nums.size();
                default: return null;
            }
        }

        /** 一个参数：可能是数字、单元格、区域（A1:B3），全部展开进 nums。 */
        Double parseArgInto(java.util.List<Double> nums) {
            skip();
            int save = pos;
            // 先尝试"区域"：REF : REF
            Double single = parseFactor();
            if (single != null && peek() == ':') {
                pos++;
                int save2 = pos;
                Double second = parseFactor();
                if (second == null) { pos = save2; return single; }
                // 这里没法直接从两个值反推区域；改走文本解析
                pos = save;
                return parseRange(nums);
            }
            if (single != null) { nums.add(single); return single; }   // 单个值只加一次
            pos = save;
            return null;
        }

        /** 解析 A1:B3 形式的区域，把其中所有数值展开。 */
        Double parseRange(java.util.List<Double> nums) {
            int st = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)))) pos++;
            String a = src.substring(st, pos);
            if (peek() != ':') return null;
            pos++;
            int st2 = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)))) pos++;
            String b = src.substring(st2, pos);
            int[] p1 = cellPos(a), p2 = cellPos(b);
            if (p1 == null || p2 == null) return null;
            int r1 = Math.min(p1[0], p2[0]), r2 = Math.max(p1[0], p2[0]);
            int c1 = Math.min(p1[1], p2[1]), c2 = Math.max(p1[1], p2[1]);
            double sum = 0;
            for (int r = r1; r <= r2; r++) {
                JSONArray row = rows.optJSONArray(r);
                if (row == null) continue;
                for (int c = c1; c <= c2; c++) {
                    Double v = numeric(row.opt(c));
                    if (v == null) continue;
                    sum += v;
                    // 关键：区域要展开成逐个数值。当成一个"和值"塞进去，
                    // AVERAGE / COUNT / MIN / MAX 就全错了（我第一版就是这么错的，
                    // 被 AVERAGE(B2:B3) 的用例抓住）。
                    nums.add(v);
                }
            }
            return sum;
        }

        /** 单元格值；引用了公式格就递归求值。 */
        Double cellValue(String ref) {
            int[] p = cellPos(ref);
            if (p == null) return null;
            JSONArray row = rows.optJSONArray(p[0]);
            if (row == null) return null;
            Object v = row.opt(p[1]);
            if (v == null || v == org.json.JSONObject.NULL) return null;
            if (v instanceof Number) return ((Number) v).doubleValue();
            String s = String.valueOf(v);
            if (s.startsWith("=")) return eval(s.substring(1), rows, depth + 1);
            return numeric(v);
        }
    }

    /** 数值化：数字原样，字符串能解析就用，其它（空、文本）返回 null。 */
    static Double numeric(Object v) {
        if (v == null || v == org.json.JSONObject.NULL) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A1 → [行(0基), 列(0基)]；不是合法引用返回 null。 */
    static int[] cellPos(String ref) {
        if (ref == null) return null;
        String s = ref.trim().toUpperCase();
        int i = 0;
        int col = 0;
        while (i < s.length() && s.charAt(i) >= 'A' && s.charAt(i) <= 'Z') {
            col = col * 26 + (s.charAt(i) - 'A' + 1);
            i++;
        }
        if (i == 0 || i >= s.length()) return null;
        try {
            int row = Integer.parseInt(s.substring(i));
            if (row < 1 || col < 1) return null;
            return new int[]{row - 1, col - 1};
        } catch (Throwable t) {
            return null;
        }
    }
}

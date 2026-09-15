package com.example.duoplus_probe;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** RFC JSON framing before Android's intentionally lenient JSONObject parser. */
public final class StrictJson {
    private StrictJson() {}

    public static JSONObject object(String text) {
        if (text == null || text.length() > 16 * 1024 * 1024)
            throw new IllegalArgumentException("Scenario text exceeds the import limit");
        Parser parser = new Parser(text);
        parser.space();
        if (parser.peek() != '{') throw parser.error("Expected a JSON object");
        parser.value(0);
        parser.space();
        if (parser.index != text.length()) throw parser.error("Unexpected trailing data");
        try { return new JSONObject(text); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid scenario JSON"); }
    }

    private static final class Parser {
        final String text;
        int index;
        Parser(String text) { this.text = text; }
        char peek() { return index == text.length() ? '\0' : text.charAt(index); }
        IllegalArgumentException error(String reason) {
            return new IllegalArgumentException(reason + " at character " + index);
        }
        void space() {
            while (index < text.length()) {
                char c = peek();
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break;
                index++;
            }
        }
        void take(char wanted) {
            if (peek() != wanted) throw error("Invalid JSON punctuation");
            index++;
        }
        void value(int depth) {
            if (depth > 32) throw error("JSON nesting exceeds 32 levels");
            space();
            switch (peek()) {
                case '{': objectValue(depth); break;
                case '[': array(depth); break;
                case '"': string(); break;
                case 't': literal("true"); break;
                case 'f': literal("false"); break;
                case 'n': literal("null"); break;
                default: number();
            }
        }
        void objectValue(int depth) {
            take('{'); space();
            if (peek() == '}') { index++; return; }
            Set<String> keys = new HashSet<>();
            while (true) {
                space();
                if (peek() != '"') throw error("Object keys must be quoted");
                if (!keys.add(string())) throw error("Duplicate JSON object key");
                space(); take(':'); value(depth + 1); space();
                if (peek() == '}') { index++; return; }
                take(',');
            }
        }
        void array(int depth) {
            take('['); space();
            if (peek() == ']') { index++; return; }
            while (true) {
                value(depth + 1); space();
                if (peek() == ']') { index++; return; }
                take(',');
            }
        }
        String string() {
            take('"');
            StringBuilder result = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') return result.toString();
                if (c < 32) throw error("Unescaped control character");
                if (c != '\\') { result.append(c); continue; }
                if (index == text.length()) throw error("Incomplete escape");
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"': case '\\': case '/': result.append(escaped); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u':
                        int point = 0;
                        for (int j = 0; j < 4; j++) {
                            if (index == text.length()) throw error("Incomplete Unicode escape");
                            char h = text.charAt(index++);
                            int digit = h >= '0' && h <= '9' ? h - '0' : h >= 'a' && h <= 'f' ? h - 'a' + 10 : h >= 'A' && h <= 'F' ? h - 'A' + 10 : -1;
                            if (digit < 0) throw error("Invalid Unicode escape");
                            point = point * 16 + digit;
                        }
                        result.append((char) point); break;
                    default: throw error("Invalid string escape");
                }
            }
            throw error("Unterminated string");
        }
        void literal(String expected) {
            if (!text.startsWith(expected, index)) throw error("Invalid JSON literal");
            index += expected.length();
        }
        boolean digit() { return peek() >= '0' && peek() <= '9'; }
        void digits() {
            if (!digit()) throw error("Expected decimal digits");
            while (digit()) index++;
        }
        void number() {
            int start = index;
            if (peek() == '-') index++;
            if (peek() == '0') index++;
            else {
                if (peek() < '1' || peek() > '9') throw error("Expected a JSON value");
                digits();
            }
            if (peek() == '.') { index++; digits(); }
            if (peek() == 'e' || peek() == 'E') {
                index++;
                if (peek() == '+' || peek() == '-') index++;
                digits();
            }
            try {
                if (!Double.isFinite(Double.parseDouble(text.substring(start, index))))
                    throw error("Scenario numbers must be finite");
            } catch (NumberFormatException invalid) { throw error("Invalid JSON number"); }
        }
    }
}

package com.example.duoplus_probe;

import java.util.List;
import java.util.Map;

/** Bounded dashboard text without constructing full strings for large radio collections. */
final class DisplayValues {
    private DisplayValues() {}

    static String format(Object value, int limit) {
        if (limit <= 0) return "";
        Writer writer = new Writer(limit);
        writer.value(value, 0);
        if (writer.truncated && writer.out.length() > 0)
            writer.out.setCharAt(writer.out.length() - 1, '…');
        return writer.out.toString();
    }

    private static final class Writer {
        final StringBuilder out = new StringBuilder();
        final int limit;
        boolean truncated;
        Writer(int limit) { this.limit = limit; }

        boolean full() { return out.length() >= limit; }
        void text(CharSequence value) {
            int remaining = limit - out.length();
            int length = Math.min(remaining, value.length());
            out.append(value, 0, length);
            if (length < value.length()) truncated = true;
        }

        void value(Object value, int depth) {
            if (full()) { truncated = true; return; }
            if (value == null) { text("unavailable"); return; }
            if (value instanceof CharSequence) { text((CharSequence) value); return; }
            if (value instanceof Number || value instanceof Boolean) { text(value.toString()); return; }
            if (depth >= 6) { text("…"); return; }
            if (value instanceof List) {
                List<?> list = (List<?>) value;
                text("[" + list.size() + " items: ");
                for (int i = 0; i < list.size(); i++) {
                    if (full()) { truncated = true; return; }
                    if (i != 0) text(", ");
                    value(list.get(i), depth + 1);
                }
                text("]");
            } else if (value instanceof Map) {
                text("{");
                boolean first = true;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (full()) { truncated = true; return; }
                    if (!first) text(", ");
                    first = false;
                    value(entry.getKey(), depth + 1); text(": "); value(entry.getValue(), depth + 1);
                }
                text("}");
            } else text("[value]");
        }
    }
}

package com.example.duoplus_probe.call;

import java.util.Locale;

/** Pure presentation/input helpers; no dialing or Telecom side effects. */
public final class CallUi {
    private CallUi() {}
    public static String dialNumber(String input) {
        if (input == null || input.length() > 100) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= '0' && c <= '9' || c == '*' || c == '#') out.append(c);
            else if (c == '+' && out.length() == 0) out.append(c);
            else if (c != ' ' && c != '-' && c != '(' && c != ')' && c != '.') return "";
        }
        return out.length() == 0 || out.toString().equals("+") ? "" : out.toString();
    }
    public static boolean isTone(char c) { return c >= '0' && c <= '9' || c == '*' || c == '#'; }
    public static String duration(long connectedElapsedMs, long nowElapsedMs) {
        if (connectedElapsedMs <= 0) return "00:00";
        long seconds = Math.max(0, nowElapsedMs - connectedElapsedMs) / 1000;
        return seconds >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }
}

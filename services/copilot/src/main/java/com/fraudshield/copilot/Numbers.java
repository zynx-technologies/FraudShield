package com.fraudshield.copilot;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Numbers {
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private Numbers() {}

    static Set<String> in(String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text == null) return found;
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            try {
                found.add(new BigDecimal(m.group().replace(",", "")).stripTrailingZeros().toPlainString());
            } catch (NumberFormatException ignored) {
                // Not a number after all.
            }
        }
        return found;
    }
}
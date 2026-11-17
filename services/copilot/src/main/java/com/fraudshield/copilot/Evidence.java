package com.fraudshield.copilot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The current case's evidence, and nothing else (design section 7). Each source has an ID the summary must cite,
 * and the facts it supports. Free text from merchants or payees is untrusted and is cleaned before use.
 */
public record Evidence(String caseId, List<Source> sources) {

    public record Source(String id, String label, Map<String, String> facts) {}

    public Source source(String id) {
        return sources.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    /** Every number that appears in the given sources, normalised (no commas, currency signs, or trailing zeros). */
    public Set<String> numbers(List<String> sourceIds) {
        Set<String> numbers = new TreeSet<>();
        for (String id : sourceIds) {
            Source s = source(id);
            if (s == null) continue;
            for (String v : s.facts().values()) numbers.addAll(Numbers.in(v));
        }
        return numbers;
    }

    public Map<String, Object> forModel() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseId", caseId);
        out.put("sources", sources);
        return out;
    }

    /** Removes control characters and template markers, and caps length. Used for any free text from outside. */
    public static String untrusted(String text) {
        if (text == null) return "";
        String clean = text.replaceAll("[\\p{Cntrl}]", " ").replace("{{", "").replace("}}", "").strip();
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }
}
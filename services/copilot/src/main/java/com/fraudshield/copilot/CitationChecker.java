package com.fraudshield.copilot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates that every statement cites a source from this case, and that every number in it appears in the cited
 * sources. Statements that recommend or state a final decision are removed: Copilot has no decision authority.
 */
@Component
public class CitationChecker {

    public record Result(List<Statement> kept, int removed) {}

    private static final List<String> DECISION_WORDS = List.of(
            "recommend", "should block", "should release", "should be blocked", "should be released", "is fraudulent",
            "is genuine", "confirm fraud", "is definitely", "must be blocked");

    public Result check(Evidence evidence, List<Statement> statements) {
        List<Statement> kept = new ArrayList<>();
        int removed = 0;
        for (Statement s : statements) {
            if (s.text() == null || s.text().isBlank() || s.sources() == null || s.sources().isEmpty()
                    || s.sources().stream().anyMatch(id -> evidence.source(id) == null)) {
                removed++;
                continue;
            }
            String lower = s.text().toLowerCase(Locale.ROOT);
            if (DECISION_WORDS.stream().anyMatch(lower::contains)) {
                removed++;
                continue;
            }
            Set<String> supported = evidence.numbers(s.sources());
            if (!supported.containsAll(Numbers.in(s.text()))) {
                removed++;
                continue;
            }
            kept.add(new Statement(s.text().strip(), List.copyOf(s.sources())));
        }
        return new Result(kept, removed);
    }
}
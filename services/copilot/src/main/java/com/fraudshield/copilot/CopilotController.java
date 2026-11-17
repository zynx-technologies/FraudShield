package com.fraudshield.copilot;

import com.fraudshield.common.web.ApiErrors;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-7: draft case summaries with links to their sources. */
@RestController
public class CopilotController {

    public record Summary(String caseId, List<Statement> statements, List<Evidence.Source> sources, int removed,
                          String generator, String notice) {}

    static final String NOTICE = "AI draft only. It does not make or recommend a final decision.";

    private final CaseSource cases;
    private final EvidenceBuilder evidence;
    private final SummaryGenerator generator;
    private final CitationChecker checker;
    private final boolean enabled;

    public CopilotController(CaseSource cases, EvidenceBuilder evidence, SummaryGenerator generator, CitationChecker checker,
                             @Value("${fraudshield.copilot.enabled:true}") boolean enabled) {
        this.cases = cases;
        this.evidence = evidence;
        this.generator = generator;
        this.checker = checker;
        this.enabled = enabled;
    }

    @PostMapping("/api/v1/copilot/cases/{caseId}/summary")
    @PreAuthorize("hasAnyRole('ANALYST', 'TEAM_LEAD')")
    public Summary summarise(@PathVariable String caseId) {
        if (!enabled) throw new ApiErrors.Unavailable("Case Copilot is switched off");
        Evidence e = evidence.build(cases.get(caseId));
        CitationChecker.Result result = checker.check(e, generator.generate(e));
        return new Summary(e.caseId(), result.kept(), e.sources(), result.removed(), generator.name(), NOTICE);
    }
}
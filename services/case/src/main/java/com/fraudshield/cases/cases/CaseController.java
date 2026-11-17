package com.fraudshield.cases.cases;

import com.fraudshield.common.security.CurrentUser;
import com.fraudshield.common.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases")
@PreAuthorize("hasAnyRole('ANALYST', 'TEAM_LEAD')")
public class CaseController {

    public record ActionRequest(@NotNull CaseService.Action action, String reason) {}

    private final CaseService cases;
    private final CurrentUser currentUser;

    public CaseController(CaseService cases, CurrentUser currentUser) {
        this.cases = cases;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<CaseView.ListItem> list(@RequestParam(defaultValue = "ACTIVE") String status) {
        return cases.list(status);
    }

    @GetMapping("/{id}")
    public CaseView get(@PathVariable String id) {
        return cases.get(id);
    }

    /** FR-5: a decision and a reason are required, and every action is audited. */
    @PostMapping("/{id}/actions")
    public CaseView act(@PathVariable String id, @Valid @RequestBody ActionRequest request) {
        return cases.act(id, request.action(), request.reason(), currentUser.id(), currentUser.hasRole(Roles.TEAM_LEAD));
    }
}
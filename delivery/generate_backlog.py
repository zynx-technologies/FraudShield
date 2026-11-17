"""Generates jira-import.csv and BACKLOG.md from backlog_data.py. Run: py generate_backlog.py"""
import csv
import os
from collections import Counter, defaultdict

from backlog_data import EMAILS, EPICS, ISSUES, OPEN_ROLES, PROJECT_KEY, REPORTER, ROLES, SPRINTS

HERE = os.path.dirname(os.path.abspath(__file__))
EPIC_NAMES = {e[0]: e[1] for e in EPICS}
JIRA_TYPE = {"Story": "Story", "Task": "Task", "Spike": "Task", "Decision": "Task"}
PHASE_FOCUS = {
    "phase-2": ("Decision core", "Months 3-6", "Decision API, rules, and fallback running in shadow; latency target proven"),
    "phase-3": ("First models", "Months 5-8", "Feature Store, fs-txn, and MLOps pipeline; beats current rules offline"),
    "phase-4": ("Investigator portal", "Months 6-9", "Queue, cases, strategy tools, and Copilot pilot; analyst acceptance"),
    "phase-5": ("Shadow and hardening", "Months 9-11", "Full-volume shadow, load, chaos, and security tests; go-live criteria met"),
    "phase-6": ("Phased rollout", "Months 11-13", "One channel at a time, step-up first; risk owner sign-off"),
}


def account(person):
    return EMAILS.get(person, person)


def assignee(owner):
    return "" if owner in OPEN_ROLES else account(owner)


def phase(issue):
    return next((x for x in issue[8].split(",") if x.startswith("phase-")), "phase-1")


def labels(issue):
    result = ["fraudshield"] + [x for x in issue[8].split(",") if x]
    if issue[2] in ("Spike", "Decision"):
        result.append(issue[2].lower())
    if issue[4] in OPEN_ROLES:
        result.append("role-" + issue[4].lower().replace(" ", "-"))
    if not issue[7] and "phase-" not in issue[8]:
        result.append("phase-1")
    return list(dict.fromkeys(result))


def description(issue):
    text = issue[9]
    if issue[4] in OPEN_ROLES:
        text += f"\n\nOwner: {issue[4]} (not yet in the team)."
    if issue[10]:
        text += "\n\nAcceptance criteria:\n" + "\n".join(f"* {a}" for a in issue[10])
    text += f"\n\nSource: FraudShield Solution Design Document, Draft 1.0."
    return text


def write_csv():
    max_labels = max(len(labels(i)) for i in ISSUES)
    header = ["Issue Id", "Parent", "Issue Type", "Summary", "Description", "Assignee", "Reporter", "Priority", "Status",
              "Story Points", "Sprint"] + ["Labels"] * max_labels
    with open(os.path.join(HERE, "jira-import.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(header)
        for key, name, text in EPICS:
            w.writerow([key, "", "Epic", name, text, "", account(REPORTER), "High", "To Do", "", "", "fraudshield"]
                       + [""] * (max_labels - 1))
        for i in ISSUES:
            lab = labels(i)
            w.writerow([i[0], i[1], JIRA_TYPE[i[2]], i[3], description(i), assignee(i[4]), account(REPORTER), i[6], "To Do",
                        i[5] or "", i[7]] + lab + [""] * (max_labels - len(lab)))


def write_markdown():
    planned = [i for i in ISSUES if i[7]]
    future = [i for i in ISSUES if not i[7]]
    lines = ["# FraudShield backlog", "",
             "Generated from `backlog_data.py`. Edit that file, then run `py generate_backlog.py`.", "",
             "Based on the FraudShield Solution Design Document, Draft 1.0 (1 November 2026). Every issue starts in **To Do**: nothing is deployed yet.", ""]

    lines += ["## Summary", "", "| Measure | Value |", "|---|---|",
              f"| Jira project key | {PROJECT_KEY} |",
              f"| Epics | {len(EPICS)} |",
              f"| Issues | {len(ISSUES)} ({len(planned)} in sprints 1 to 6, {len(future)} future-phase) |",
              f"| Points planned in sprints 1 to 6 | {sum(i[5] for i in planned)} |",
              f"| Future-phase points (rough) | {sum(i[5] for i in future)} |",
              f"| Points that need roles not yet in the team | {sum(i[5] for i in ISSUES if i[4] in OPEN_ROLES)} |", ""]
    lines += [f"**Reporter on every ticket:** {REPORTER} ({ROLES[REPORTER]}). Assignees are matched by Jira account email.", ""]

    lines += ["## Sprints", "", "Sprints 1 to 6 cover Phase 1 (Foundation) of the design: data access, Azure landing zone, CI/CD, and labelled history.", "",
              "| Sprint | Dates | Goal | Issues | Points |", "|---|---|---|---|---|"]
    for name, start, end, goal in SPRINTS:
        items = [i for i in ISSUES if i[7] == name]
        lines.append(f"| {name} | {start} to {end} | {goal} | {len(items)} | {sum(i[5] for i in items)} |")
    lines += ["", "Sprint 3 is short for the Christmas shutdown. UK bank holidays are not otherwise deducted.", ""]

    lines += ["## Load by person", "", "Points in sprints 1 to 6. Use this to rebalance at sprint planning.", "",
              "| Person | Role | Jira account | " + " | ".join(s[0].replace("Sprint ", "S") for s in SPRINTS) + " | Total | Future |",
              "|---|---|---|" + "---|" * (len(SPRINTS) + 2)]
    for person, role in ROLES.items():
        per = [sum(i[5] for i in ISSUES if i[4] == person and i[7] == s[0]) for s in SPRINTS]
        fut = sum(i[5] for i in future if i[4] == person)
        lines.append(f"| {person} | {role} | {EMAILS[person]} | " + " | ".join(map(str, per)) + f" | {sum(per)} | {fut} |")
    for role in OPEN_ROLES:
        per = [sum(i[5] for i in ISSUES if i[4] == role and i[7] == s[0]) for s in SPRINTS]
        fut = sum(i[5] for i in future if i[4] == role)
        if sum(per) or fut:
            lines.append(f"| **Not yet in the team** | {role} | unassigned | " + " | ".join(map(str, per)) + f" | {sum(per)} | {fut} |")
    lines.append("")

    lines += ["## Blockers", "", "These stop other work if they slip.", ""]
    for i in ISSUES:
        if "blocker" in i[8]:
            lines.append(f"- **{i[0]}** {i[3]} ({i[4]}, {i[7]})")
    lines.append("")

    lines += ["## Open questions from section 18", "", "| ID | Question | Owner | Sprint |", "|---|---|---|---|"]
    for i in ISSUES:
        if "open-question" in i[8]:
            lines.append(f"| {i[0]} | {i[3]} | {i[4]} | {i[7]} |")
    lines.append("")

    by_epic = defaultdict(list)
    for i in ISSUES:
        by_epic[i[1]].append(i)
    lines += ["## Epics", "", "| Epic | Name | Issues | Points |", "|---|---|---|---|"]
    for key, name, _ in EPICS:
        lines.append(f"| {key} | {name} | {len(by_epic[key])} | {sum(i[5] for i in by_epic[key])} |")
    lines.append("")

    lines += ["## Backlog by epic", ""]
    for key, name, text in EPICS:
        lines += [f"### {key}: {name}", "", text, "", "| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |",
                  "|---|---|---|---|---|---|---|"]
        for i in by_epic[key]:
            owner = f"{i[4]} *(to fill)*" if i[4] in OPEN_ROLES else i[4]
            lines.append(f"| {i[0]} | {i[2]} | {i[3]} | {owner} | {i[5]} | {i[6]} | {i[7] or phase(i).replace('-', ' ').title()} |")
        lines.append("")

    phases = defaultdict(list)
    for i in future:
        phases[phase(i)].append(i)
    lines += ["## Future roadmap", "", "Work after Phase 1, from section 15 of the design. Points are rough and most of it needs the hires in section 15.", "",
              "| Phase | Name | Months | Outcome and exit gate | Items | Rough points |", "|---|---|---|---|---|---|"]
    for ph in sorted(phases):
        name, months, outcome = PHASE_FOCUS[ph]
        lines.append(f"| {ph[-1]} | {name} | {months} | {outcome} | {len(phases[ph])} | {sum(i[5] for i in phases[ph])} |")
    lines.append("")

    lines += ["## Definition of done", "",
              "- Reviewed by someone other than the author (segregation of duties applies to rules, models, and production changes)",
              "- Changes made through a pull request, and ci.yml passes",
              "- Infrastructure changed only through Terraform and infra.yml, never by hand in the portal",
              "- Deployed to dev, and checked by the tester",
              "- No card numbers, account identifiers, or customer data in logs, tickets, or screenshots",
              "- README or runbook updated where behaviour changes", ""]

    lines += ["## Importing into Jira", "",
              "1. Create the Jira project with key `FS` (Scrum template). Ask a Jira admin to confirm the Story Points and Sprint fields are on the Story and Task screens.",
              "2. Optional: create Sprint 1 to Sprint 6 on the board first, with the dates above. If you skip this, the importer creates sprints by name.",
              "3. Go to **Settings > System > External system import > CSV**, choose `jira-import.csv`, and pick the FS project. File encoding is UTF-8.",
              "4. Map the columns:",
              "   - **Issue Id** to Issue Id, and **Parent** to Parent. This links each story and task to its epic (E1 to E16).",
              "   - **Issue Type**, **Summary**, **Description**, **Priority**, **Status** to the fields of the same name.",
              "   - **Assignee** and **Reporter** to the user fields. They hold Jira account emails, so they match automatically.",
              "   - **Story Points** to your story point field (Story point estimate in team-managed projects).",
              "   - **Sprint** to Sprint. Future-phase issues have no sprint and land in the backlog.",
              "   - Every **Labels** column to Labels. Jira joins the repeated columns.",
              "5. Check the status mapping: To Do must match your workflow.",
              "6. After import, filter `labels in (role-java-engineer, role-ml-engineer, role-angular-developer, role-devops-engineer, role-security-engineer, role-model-risk-owner, role-fraud-strategist)` to find work waiting for new hires.", ""]
    with open(os.path.join(HERE, "BACKLOG.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


if __name__ == "__main__":
    ids = [i[0] for i in ISSUES]
    assert len(ids) == len(set(ids)), "Duplicate issue ID"
    assert all(i[1] in EPIC_NAMES for i in ISSUES), "Unknown epic"
    owners = set(ROLES) | set(OPEN_ROLES)
    assert all(i[4] in owners for i in ISSUES), [i[4] for i in ISSUES if i[4] not in owners]
    assert all(i[7] in {s[0] for s in SPRINTS} | {""} for i in ISSUES), "Unknown sprint"
    assert all(i[6] in ("Highest", "High", "Medium", "Low") for i in ISSUES), "Unknown priority"
    assert all(i[5] in (1, 2, 3, 5, 8, 13) for i in ISSUES), [i[0] for i in ISSUES if i[5] not in (1, 2, 3, 5, 8, 13)]
    assert all(i[7] or "phase-" in i[8] for i in ISSUES), [i[0] for i in ISSUES if not i[7] and "phase-" not in i[8]]
    write_csv()
    write_markdown()
    print(f"{len(EPICS)} epics, {len(ISSUES)} issues written")

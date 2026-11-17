import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, Policy, problem, Rule, RuleChange, RuleType, Simulation, Thresholds } from '../api';
import { AuthService } from '../auth';
import { ACTION_LABELS, compact, money, time, titleCase } from '../ui/format';
import { ErrorMessage, PageTitle, Pill } from '../ui/page';
import { autoReload } from '../ui/reload';

/** Screen 4: rules and score thresholds, for strategists. Changes need a second approver from risk (FR-8). */
@Component({
  selector: 'fs-rules',
  imports: [PageTitle, Pill, ErrorMessage, FormsModule],
  template: `
    <fs-page-title title="Rules and score thresholds" />
    <fs-error [message]="error()" />
    <div class="grid half">
      <section class="card">
        <h2>Active rules</h2>
        <table>
          <thead><tr><th scope="col">Rule</th><th scope="col">Action</th><th scope="col">Hits, 7 days</th><th scope="col">Precision</th><th scope="col">Status</th></tr></thead>
          <tbody>
            @for (r of rules(); track r.id) {
              <tr [class.selected]="selected()?.id === r.id" class="clickable" (click)="selected.set(r)" tabindex="0" (keydown.enter)="selected.set(r)">
                <td>{{ r.name }}</td>
                <td>{{ actionLabel(r.action) }}</td>
                <td>{{ compact(r.hits7d) }}</td>
                <td>{{ r.precisionPct == null ? '–' : r.precisionPct + '%' }}</td>
                <td><fs-pill [tone]="statusTone(r.status)">{{ titleCase(r.status) }}</fs-pill>
                  @if (r.pending && r.status !== 'REVIEW') { <fs-pill tone="amber">Change pending</fs-pill> }</td>
              </tr>
            } @empty { <tr><td colspan="5" class="muted">No rules yet.</td></tr> }
          </tbody>
        </table>
        <div class="actions">
          @if (auth.hasRole('STRATEGIST')) { <button type="button" class="primary" (click)="newRule.set(!newRule())">New rule</button> }
          <button type="button" [disabled]="!selected()" (click)="backtest()">Backtest</button>
          <button type="button" (click)="toggleHistory()">Change history</button>
        </div>
        @if (message()) { <p class="note" role="status">{{ message() }}</p> }

        @if (selected()?.pending; as p) {
          <div class="panel" aria-label="Pending change">
            <h3>Waiting for approval: {{ selected()!.name }} v{{ p.version }}</h3>
            <p>{{ actionLabel(p.action) }}, {{ titleCase(p.mode) }}. Proposed by {{ p.author }} at {{ time(p.createdAt) }}.</p>
            @if (auth.hasRole('RISK_APPROVER')) {
              <label class="field" for="rule-reason">Reason</label>
              <input id="rule-reason" [(ngModel)]="ruleReason" name="ruleReason" />
              <div class="actions">
                <button type="button" class="primary" [disabled]="ruleReason.trim().length < 5" (click)="decideRule(true)">Approve</button>
                <button type="button" [disabled]="ruleReason.trim().length < 5" (click)="decideRule(false)">Reject</button>
              </div>
            }
          </div>
        }

        @if (newRule()) {
          <form class="panel" (ngSubmit)="createRule()" aria-label="New rule">
            <h3>New rule</h3>
            <label class="field" for="rule-name">Name</label>
            <input id="rule-name" [(ngModel)]="draft.name" name="name" required maxlength="120" />
            <label class="field" for="rule-type">Type</label>
            <select id="rule-type" [(ngModel)]="draft.type" name="type" (ngModelChange)="draft.params = {}">
              @for (t of types(); track t.type) { <option [value]="t.type">{{ t.description }}</option> }
            </select>
            @for (p of draftType()?.params ?? []; track p.name) {
              <label class="field" [for]="'p-' + p.name">{{ p.label }}</label>
              <input [id]="'p-' + p.name" [(ngModel)]="draft.params[p.name]" [name]="'p-' + p.name" />
            }
            <label class="field" for="rule-action">Action</label>
            <select id="rule-action" [(ngModel)]="draft.action" name="action">
              <option value="BLOCK">Block</option><option value="HOLD">Hold</option><option value="STEP_UP">Step-up</option><option value="MONITOR">Monitor</option>
            </select>
            <label class="field" for="rule-mode">Start in</label>
            <select id="rule-mode" [(ngModel)]="draft.mode" name="mode">
              <option value="SHADOW">Shadow (counted, no effect)</option><option value="LIVE">Live</option>
            </select>
            <div class="actions"><button type="submit" class="primary">Submit for approval</button></div>
          </form>
        }

        @if (history(); as h) {
          <h3>Change history</h3>
          <table>
            <thead><tr><th scope="col">Rule</th><th scope="col">Version</th><th scope="col">Change</th><th scope="col">Author</th><th scope="col">Decision</th></tr></thead>
            <tbody>
              @for (c of h; track $index) {
                <tr><td>{{ c.ruleName }}</td><td>v{{ c.version }}</td><td>{{ actionLabel(c.action) }}, {{ titleCase(c.mode) }}</td><td>{{ c.author }}</td>
                  <td>{{ titleCase(c.status) }}{{ c.decidedBy ? ' by ' + c.decidedBy : '' }}</td></tr>
              }
            </tbody>
          </table>
        }
      </section>

      <section class="card">
        <h2>Threshold simulator: last {{ simulation()?.windowDays ?? 30 }} days</h2>
        @for (s of sliders; track s.key) {
          <div class="slider">
            <label [for]="'t-' + s.key"><span>{{ s.label }}</span><strong>{{ proposed()[s.key] }}</strong></label>
            <input type="range" min="1" max="99" [id]="'t-' + s.key" [value]="proposed()[s.key]" (change)="setThreshold(s.key, +$any($event.target).value)" />
          </div>
        }
        @if (simulation(); as sim) {
          <table>
            <thead><tr><th scope="col">Measure</th><th scope="col">Current</th><th scope="col">Proposed</th></tr></thead>
            <tbody>
              <tr><td>Fraud value stopped</td><td>{{ moneyShort(sim.current.fraudValueStopped) }}</td><td>{{ moneyShort(sim.proposed.fraudValueStopped) }}</td></tr>
              <tr><td>Fraud recall</td><td>{{ sim.current.fraudRecallPct }}%</td><td>{{ sim.proposed.fraudRecallPct }}%</td></tr>
              <tr><td>Alerts per day</td><td>{{ compact(sim.current.alertsPerDay) }}</td><td>{{ compact(sim.proposed.alertsPerDay) }}</td></tr>
              <tr><td>False-positive ratio</td><td>{{ ratio(sim.current.falsePositiveRatio) }}</td><td>{{ ratio(sim.proposed.falsePositiveRatio) }}</td></tr>
              <tr><td>Step-ups per day</td><td>{{ compact(sim.current.stepUpsPerDay) }}</td><td>{{ compact(sim.proposed.stepUpsPerDay) }}</td></tr>
            </tbody>
          </table>
        }
        <fs-error [message]="simError()" />
        @if (pending(); as p) {
          <div class="panel">
            <h3>Waiting for approval</h3>
            <p>Step-up {{ p.stepUp }}, hold {{ p.hold }}, block {{ p.block }}. Proposed by {{ p.author }}: “{{ p.reason }}”</p>
            @if (auth.hasRole('RISK_APPROVER')) {
              <label class="field" for="th-decision">Reason</label>
              <input id="th-decision" [(ngModel)]="thresholdDecision" name="thresholdDecision" />
              <div class="actions">
                <button type="button" class="primary" [disabled]="thresholdDecision.trim().length < 5" (click)="decideThresholds(p, true)">Approve</button>
                <button type="button" [disabled]="thresholdDecision.trim().length < 5" (click)="decideThresholds(p, false)">Reject</button>
              </div>
            }
          </div>
        } @else if (auth.hasRole('STRATEGIST')) {
          <label class="field" for="th-reason">Reason for the change</label>
          <input id="th-reason" [(ngModel)]="thresholdReason" name="thresholdReason" />
          <div class="actions">
            <button type="button" class="primary" [disabled]="!changed() || thresholdReason.trim().length < 5" (click)="propose()">Submit for approval</button>
          </div>
        }
        <p class="note">Needs a second approver from risk. Recall and value count fraud scored at or above the hold threshold.</p>
      </section>
    </div>
  `,
})
export class RulesPage {
  private api = inject(Api);
  protected auth = inject(AuthService);
  protected rules = signal<Rule[]>([]);
  protected types = signal<RuleType[]>([]);
  protected selected = signal<Rule | null>(null);
  protected history = signal<RuleChange[] | null>(null);
  protected newRule = signal(false);
  protected error = signal<string | null>(null);
  protected message = signal<string | null>(null);
  protected active = signal<Thresholds>({ stepUp: 60, hold: 80, block: 99 });
  protected proposed = signal<Thresholds>({ stepUp: 60, hold: 80, block: 99 });
  protected pending = signal<Policy | null>(null);
  protected simulation = signal<Simulation | null>(null);
  protected simError = signal<string | null>(null);
  protected changed = computed(() => {
    const a = this.active(), p = this.proposed();
    return a.stepUp !== p.stepUp || a.hold !== p.hold || a.block !== p.block;
  });
  protected sliders: { key: 'stepUp' | 'hold' | 'block'; label: string }[] = [
    { key: 'stepUp', label: 'Step-up from score' }, { key: 'hold', label: 'Hold from score' }, { key: 'block', label: 'Block from score' },
  ];
  protected draft = { name: '', type: 'AMOUNT_OVER', action: 'HOLD', mode: 'SHADOW', params: {} as Record<string, string> };
  protected ruleReason = '';
  protected thresholdReason = '';
  protected thresholdDecision = '';
  protected compact = compact;
  protected time = time;
  protected titleCase = titleCase;
  protected draftType() { return this.types().find((t) => t.type === this.draft.type); }

  constructor() {
    autoReload(() => this.load());
  }

  load() {
    this.api.rules().subscribe({
      next: (r) => {
        this.rules.set(r);
        const sel = this.selected();
        this.selected.set(sel ? (r.find((x) => x.id === sel.id) ?? null) : null);
        this.error.set(null);
      },
      error: (e) => this.error.set(problem(e)),
    });
    this.api.ruleTypes().subscribe({ next: (t) => this.types.set(t), error: () => undefined });
    this.api.thresholds().subscribe({
      next: (t) => {
        this.active.set(t.active);
        this.pending.set(t.pending);
        this.proposed.set(t.pending ? { stepUp: t.pending.stepUp, hold: t.pending.hold, block: t.pending.block } : { ...t.active });
        this.simulate();
      },
      error: (e) => this.simError.set(problem(e)),
    });
  }

  setThreshold(key: 'stepUp' | 'hold' | 'block', value: number) {
    this.proposed.set({ ...this.proposed(), [key]: value });
    this.simulate();
  }

  simulate() {
    const p = this.proposed();
    if (!(p.stepUp < p.hold && p.hold < p.block)) {
      this.simError.set('Step-up must be below hold, and hold below block.');
      return;
    }
    this.api.simulate(p).subscribe({
      next: (s) => { this.simulation.set(s); this.simError.set(null); },
      error: (e) => this.simError.set(problem(e)),
    });
  }

  propose() {
    this.api.proposeThresholds(this.proposed(), this.thresholdReason.trim()).subscribe({
      next: () => { this.thresholdReason = ''; this.message.set('Threshold change submitted for approval.'); this.load(); },
      error: (e) => this.simError.set(problem(e)),
    });
  }

  decideThresholds(p: Policy, approve: boolean) {
    this.api.decideThresholds(p.id, approve, this.thresholdDecision.trim()).subscribe({
      next: () => { this.thresholdDecision = ''; this.message.set(approve ? 'Thresholds approved and live.' : 'Threshold change rejected.'); this.load(); },
      error: (e) => this.simError.set(problem(e)),
    });
  }

  createRule() {
    this.api.createRule(this.draft).subscribe({
      next: (r) => {
        this.newRule.set(false);
        this.message.set(`${r.id} submitted. It goes live only after a second approver from risk accepts it.`);
        this.draft = { name: '', type: 'AMOUNT_OVER', action: 'HOLD', mode: 'SHADOW', params: {} };
        this.load();
      },
      error: (e) => this.error.set(problem(e)),
    });
  }

  decideRule(approve: boolean) {
    const r = this.selected();
    if (!r?.pending) return;
    this.api.decideRule(r.id, r.pending.version, approve, this.ruleReason.trim()).subscribe({
      next: () => { this.ruleReason = ''; this.message.set(approve ? `${r.id} approved.` : `${r.id} rejected.`); this.load(); },
      error: (e) => this.error.set(problem(e)),
    });
  }

  backtest() {
    const r = this.selected();
    if (!r) return;
    this.api.backtest(r.id).subscribe({
      next: () => this.message.set('Backtest started.'),
      error: (e) => this.message.set(problem(e)),
    });
  }

  toggleHistory() {
    if (this.history()) {
      this.history.set(null);
      return;
    }
    this.api.ruleHistory().subscribe({ next: (h) => this.history.set(h), error: (e) => this.error.set(problem(e)) });
  }

  actionLabel(a: string) { return ACTION_LABELS[a] ?? a; }
  statusTone(s: string) { return s === 'LIVE' ? 'green' : s === 'REVIEW' ? 'amber' : 'grey'; }
  ratio(r: number | null) { return r == null ? '–' : `${r} : 1`; }
  moneyShort(v: number) { return v >= 1_000_000 ? `£${(v / 1_000_000).toFixed(2)}M` : money(v, 'GBP', true); }
}
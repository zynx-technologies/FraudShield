import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, CaseAction, CaseView, problem, Summary } from '../api';
import { AuthService } from '../auth';
import { CHANNEL_LABELS, money, scoreTone, time } from '../ui/format';
import { EntityGraph } from '../ui/entity-graph';
import { Bar, ErrorMessage, PageTitle, Pill } from '../ui/page';
import { autoReload } from '../ui/reload';

/** Screen 3: the transaction, model reasons, linked entities, the AI draft, and the available actions. */
@Component({
  selector: 'fs-case-investigation',
  imports: [PageTitle, Pill, Bar, ErrorMessage, EntityGraph, FormsModule],
  template: `
    @if (view(); as c) {
      <fs-page-title [title]="'Case ' + c.id"><fs-pill [tone]="tone(c.score)">Score {{ c.score }}</fs-pill></fs-page-title>
      <fs-error [message]="error()" />
      <div class="grid three">
        <section class="card">
          <h2>Transaction</h2>
          <table class="facts">
            <tbody>
              <tr><th scope="row">Amount</th><td><strong>{{ money(c.transaction.amount, c.transaction.currency) }}</strong> {{ channelText(c.transaction.channel) }}</td></tr>
              <tr><th scope="row">Account</th><td>{{ c.transaction.accountMasked }} (masked)</td></tr>
              @if (c.transaction.payee) {
                <tr><th scope="row">Payee</th><td>{{ c.transaction.payee }}{{ c.transaction.payeeFirstPayment ? ', first payment' : '' }}</td></tr>
              }
              @if (c.transaction.deviceId) {
                <tr><th scope="row">Device</th><td>{{ c.transaction.deviceId }}{{ c.transaction.deviceFirstSeenToday ? ', first seen today' : '' }}</td></tr>
              }
              <tr><th scope="row">Decision</th><td><fs-pill tone="red">{{ c.transaction.decision }}</fs-pill>
                @if (c.transaction.latencyMs != null) { {{ c.transaction.latencyMs }} ms }</td></tr>
              @if (c.transaction.usualMedian != null) {
                <tr><th scope="row">Usual payment</th><td>{{ money(c.transaction.usualMedian, c.transaction.currency, true) }} median, 90 days</td></tr>
              }
            </tbody>
          </table>
        </section>

        <section class="card">
          <h2>Why the model flagged this</h2>
          @for (r of c.reasons; track r.code) {
            <fs-bar [label]="r.label" [valueText]="'+' + r.value.toFixed(2)" [percent]="r.value / maxReason() * 90" />
          } @empty { <p class="muted">No model reasons: this alert came from rules.</p> }
          @for (h of c.ruleHits; track h.ruleId) {
            @if (h.live) { <p class="rule-hit">Rule: {{ h.name }}</p> }
          }
          <p class="note">Reason codes from {{ c.transaction.modelVersion ?? 'the model' }}. Shows model drivers, not proof of fraud.</p>
        </section>

        <section class="card">
          <h2>Actions</h2>
          @if (c.outcome) {
            <p class="outcome" role="status"><strong>{{ c.outcome.label === 'CONFIRMED_FRAUD' ? 'Confirmed fraud' : 'Genuine' }}</strong>,
              decided by {{ c.outcome.decidedBy }} at {{ time(c.outcome.decidedAt) }}.</p>
          }
          @if (canAct()) {
            <label class="field" for="reason">Reason <span class="muted">(required)</span></label>
            <textarea id="reason" rows="2" [(ngModel)]="reason" name="reason" maxlength="500"
              placeholder="For example: customer confirmed they did not make this payment"></textarea>
            <div class="actions">
              <button type="button" class="danger" [disabled]="!reasonOk() || busy()" (click)="act('CONFIRM_FRAUD')">Confirm fraud and block</button>
              <button type="button" [disabled]="!reasonOk() || busy()" (click)="act('MARK_GENUINE')">Mark genuine and release</button>
              @if (c.status !== 'CLOSED') {
                <button type="button" [disabled]="!reasonOk() || busy()" (click)="act('CALL_CUSTOMER')">Call customer</button>
                <button type="button" [disabled]="!reasonOk() || busy()" (click)="act('ESCALATE_AML')">Escalate to AML</button>
              }
            </div>
            <p class="note">A decision and reason are required. Every action is audited.</p>
          } @else if (c.status === 'CLOSED') {
            <p class="note">This case is closed. A team lead can record a correction.</p>
          }
          <h3>History</h3>
          <ul class="history">
            @for (h of c.history; track $index) { <li>{{ time(h.at) }} {{ h.detail }}</li> }
          </ul>
        </section>
      </div>

      <div class="grid two-one">
        <section class="card">
          <h2>AI case summary (draft)</h2>
          @if (summary(); as s) {
            <div class="draft">
              @for (st of s.statements; track $index) { <p>{{ st.text }}</p> }
              <p class="sources">Sources: {{ sourceText(s) }}</p>
            </div>
            @if (s.removed > 0) { <p class="note">{{ s.removed }} drafted statement(s) were removed because they were not supported by the evidence.</p> }
            <p class="note">{{ s.notice }}</p>
          } @else {
            <button type="button" [disabled]="summaryBusy()" (click)="draft()">{{ summaryBusy() ? 'Drafting…' : 'Draft summary' }}</button>
            <fs-error [message]="summaryError()" />
            <p class="note">AI draft only. It does not make or recommend a final decision.</p>
          }
        </section>
        <section class="card">
          <h2>Linked entities</h2>
          <fs-entity-graph [links]="c.links" />
        </section>
      </div>
    } @else {
      <fs-error [message]="error()" />
      @if (!error()) { <p class="muted">Loading…</p> }
    }
  `,
})
export class CaseInvestigationPage {
  id = input.required<string>();
  private api = inject(Api);
  private auth = inject(AuthService);
  protected view = signal<CaseView | null>(null);
  protected error = signal<string | null>(null);
  protected summary = signal<Summary | null>(null);
  protected summaryError = signal<string | null>(null);
  protected summaryBusy = signal(false);
  protected busy = signal(false);
  protected reason = '';
  protected money = money;
  protected time = time;
  protected maxReason = computed(() => Math.max(0.01, ...(this.view()?.reasons ?? []).map((r) => r.value)));

  constructor() {
    // Load when the case ID arrives from the route, and again if it changes.
    effect(() => {
      this.id();
      untracked(() => { this.summary.set(null); this.load(); });
    });
    autoReload(() => this.load(), undefined, false);
  }

  load() {
    this.api.case(this.id()).subscribe({
      next: (c) => { this.view.set(c); this.error.set(null); },
      error: (e) => this.error.set(problem(e)),
    });
  }

  /** Analysts act on open cases. Only team leads can correct a closed one. */
  canAct() {
    const c = this.view();
    if (!c) return false;
    if (c.status === 'CLOSED') return this.auth.hasRole('TEAM_LEAD');
    return this.auth.hasRole('ANALYST') || this.auth.hasRole('TEAM_LEAD');
  }

  reasonOk() { return this.reason.trim().length >= 5; }

  act(action: CaseAction) {
    this.busy.set(true);
    this.api.act(this.id(), action, this.reason.trim()).subscribe({
      next: (c) => { this.view.set(c); this.reason = ''; this.error.set(null); this.busy.set(false); },
      error: (e) => { this.error.set(problem(e)); this.busy.set(false); },
    });
  }

  draft() {
    this.summaryBusy.set(true);
    this.api.summary(this.id()).subscribe({
      next: (s) => { this.summary.set(s); this.summaryBusy.set(false); },
      error: (e) => { this.summaryError.set(problem(e)); this.summaryBusy.set(false); },
    });
  }

  sourceText(s: Summary) {
    const used = new Set(s.statements.flatMap((st) => st.sources));
    return s.sources.filter((src) => used.has(src.id)).map((src) => src.label).join(', ');
  }

  tone(s: number) { return scoreTone(s); }
  channelText(c: string) {
    return { INSTANT: 'instant payment', CARD: 'card payment', TRANSFER: 'online transfer' }[c] ?? (CHANNEL_LABELS[c] ?? c);
  }
}
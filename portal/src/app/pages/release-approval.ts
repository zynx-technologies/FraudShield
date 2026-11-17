import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api, problem, Release } from '../api';
import { AuthService } from '../auth';
import { titleCase } from '../ui/format';
import { ErrorMessage, PageTitle, Pill } from '../ui/page';
import { autoReload } from '../ui/reload';

/** Screen 6: the gate evidence a model risk owner needs to approve a canary release (design section 8). */
@Component({
  selector: 'fs-release-approval',
  imports: [PageTitle, Pill, ErrorMessage, FormsModule],
  template: `
    @if (release(); as r) {
      <fs-page-title [title]="'Release approval: ' + r.model + ' v' + r.version" />
      <fs-error [message]="error()" />
      <div class="grid two-one">
        <section class="card">
          <h2>Promotion gates</h2>
          <table>
            <thead><tr><th scope="col">Gate</th><th scope="col">Result</th><th scope="col">Status</th></tr></thead>
            <tbody>
              @for (g of r.gates; track g.gate) {
                <tr><td>{{ g.gate }}</td><td>{{ g.result }}</td>
                  <td><fs-pill [tone]="g.status === 'PASSED' ? 'green' : g.status === 'PENDING' ? 'amber' : 'red'">{{ titleCase(g.status) }}</fs-pill></td></tr>
              }
            </tbody>
          </table>
        </section>
        <section class="card">
          <h2>Decision</h2>
          <dl class="decision">
            <div><dt>Requested by</dt><dd>{{ r.requestedBy }}</dd></div>
            <div><dt>Approver</dt><dd>{{ r.decidedBy ?? 'Model risk owner' }}</dd></div>
            <div><dt>Rollback</dt><dd>{{ r.rollbackTo ? 'Automatic, to v' + r.rollbackTo : 'Automatic' }}</dd></div>
            <div><dt>Model card</dt><dd>@if (r.modelCardUrl) { <a [href]="r.modelCardUrl" target="_blank" rel="noopener">View document</a> } @else { – }</dd></div>
            <div><dt>Lineage</dt><dd>@if (r.lineageUrl) { <a [href]="r.lineageUrl" target="_blank" rel="noopener">MLflow run</a> } @else { – }</dd></div>
            <div><dt>Status</dt><dd><fs-pill [tone]="r.status === 'APPROVED' ? 'green' : r.status === 'PENDING' ? 'amber' : 'red'">{{ titleCase(r.status) }}</fs-pill></dd></div>
          </dl>
          @if (r.status === 'PENDING') {
            @if (canDecide(r)) {
              <label class="field" for="release-reason">Reason</label>
              <input id="release-reason" [(ngModel)]="reason" name="reason" />
              <div class="actions">
                <button type="button" class="primary" [disabled]="!r.readyForDecision || reason.trim().length < 5" (click)="decide(true)">Approve canary</button>
                <button type="button" [disabled]="reason.trim().length < 5" (click)="decide(false)">Reject</button>
              </div>
              @if (!r.readyForDecision) { <p class="note">Every gate before approval must pass first.</p> }
            } @else if (isAuthor(r)) {
              <p class="note" role="status">You trained or requested this model, so you cannot approve it.</p>
            } @else {
              <p class="note">Only the model risk owner can decide.</p>
            }
          } @else if (r.decisionReason) {
            <p class="note">“{{ r.decisionReason }}”</p>
          }
          <p class="note">The person who trained a model cannot approve it.</p>
        </section>
      </div>
    } @else {
      <fs-page-title title="Release approval" />
      <fs-error [message]="error()" />
      @if (!error()) { <p class="muted">{{ empty() ? 'No release is waiting for approval.' : 'Loading…' }}</p> }
    }
  `,
})
export class ReleaseApprovalPage {
  model = input<string>();
  version = input<string>();
  private api = inject(Api);
  private auth = inject(AuthService);
  private router = inject(Router);
  protected release = signal<Release | null>(null);
  protected error = signal<string | null>(null);
  protected empty = signal(false);
  protected reason = '';
  protected titleCase = titleCase;

  constructor() {
    effect(() => {
      this.model();
      this.version();
      untracked(() => this.load());
    });
    autoReload(() => this.load(), undefined, false);
  }

  load() {
    const model = this.model(), version = this.version();
    if (model && version) {
      this.api.release(model, +version).subscribe({
        next: (r) => { this.release.set(r); this.error.set(null); },
        error: (e) => this.error.set(problem(e)),
      });
      return;
    }
    // No version in the address: open the pending release, if there is one.
    this.api.monitoring('fs-txn').subscribe({
      next: (m) => m.pendingRelease ? this.router.navigate(['/releases', m.model, m.pendingRelease]) : this.empty.set(true),
      error: (e) => this.error.set(problem(e)),
    });
  }

  isAuthor(r: Release) {
    const me = this.auth.userId().toLowerCase();
    return me === (r.trainedBy ?? '').toLowerCase() || me === r.requestedBy.toLowerCase();
  }

  canDecide(r: Release) { return this.auth.hasRole('MODEL_RISK_OWNER') && !this.isAuthor(r); }

  decide(approve: boolean) {
    const r = this.release()!;
    this.api.decideRelease(r.model, r.version, approve, this.reason.trim()).subscribe({
      next: (updated) => { this.release.set(updated); this.reason = ''; this.error.set(null); },
      error: (e) => this.error.set(problem(e)),
    });
  }
}
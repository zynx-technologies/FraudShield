import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, CaseListItem, problem } from '../api';
import { CHANNEL_LABELS, money, scoreTone, time, titleCase } from '../ui/format';
import { ErrorMessage, PageTitle, Pill } from '../ui/page';
import { autoReload } from '../ui/reload';

@Component({
  selector: 'fs-cases',
  imports: [PageTitle, Pill, ErrorMessage, RouterLink],
  template: `
    <fs-page-title title="Cases" />
    <div class="toolbar" role="toolbar" aria-label="Case status">
      @for (s of statuses; track s.value) {
        <button type="button" class="chip" [class.on]="status() === s.value" [attr.aria-pressed]="status() === s.value" (click)="status.set(s.value); load()">{{ s.label }}</button>
      }
    </div>
    <fs-error [message]="error()" />
    <section class="card">
      <table>
        <thead><tr><th scope="col">Case</th><th scope="col">Score</th><th scope="col">Amount</th><th scope="col">Channel</th>
          <th scope="col">Main reason</th><th scope="col">Assigned to</th><th scope="col">Opened</th><th scope="col">Status</th></tr></thead>
        <tbody>
          @for (c of cases(); track c.id) {
            <tr>
              <td><a [routerLink]="['/cases', c.id]">{{ c.id }}</a></td>
              <td><fs-pill [tone]="tone(c.score)">{{ c.score }}</fs-pill></td>
              <td>{{ money(c.amount, c.currency) }}</td>
              <td>{{ channel(c.channel) }}</td>
              <td>{{ c.mainReason }}</td>
              <td>{{ c.assignedTo ?? 'Unassigned' }}</td>
              <td>{{ time(c.createdAt) }}</td>
              <td><fs-pill [tone]="c.status === 'CLOSED' ? 'grey' : c.status === 'ESCALATED' ? 'amber' : 'green'">{{ statusText(c) }}</fs-pill></td>
            </tr>
          } @empty { <tr><td colspan="8" class="muted">No cases.</td></tr> }
        </tbody>
      </table>
    </section>
  `,
})
export class CasesPage {
  private api = inject(Api);
  protected cases = signal<CaseListItem[]>([]);
  protected error = signal<string | null>(null);
  protected status = signal('ACTIVE');
  protected statuses = [{ value: 'ACTIVE', label: 'Active' }, { value: 'ESCALATED', label: 'Escalated' }, { value: 'CLOSED', label: 'Closed' }];
  protected money = money;
  protected time = time;

  constructor() { autoReload(() => this.load()); }

  load() {
    this.api.cases(this.status()).subscribe({
      next: (c) => { this.cases.set(c); this.error.set(null); },
      error: (e) => this.error.set(problem(e)),
    });
  }

  tone(s: number) { return scoreTone(s); }
  channel(c: string) { return CHANNEL_LABELS[c] ?? c; }
  statusText(c: CaseListItem) {
    if (c.outcome) return c.outcome === 'CONFIRMED_FRAUD' ? 'Fraud' : titleCase(c.outcome);
    return titleCase(c.status);
  }
}
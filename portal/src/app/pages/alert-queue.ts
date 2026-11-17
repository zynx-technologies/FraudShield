import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Api, problem, Queue } from '../api';
import { CHANNEL_LABELS, minutesLeft, money, scoreTone, slaText } from '../ui/format';
import { ErrorMessage, PageTitle, Pill } from '../ui/page';
import { autoReload } from '../ui/reload';

/** Screen 2: the analyst's queue, ordered by risk and time remaining. */
@Component({
  selector: 'fs-alert-queue',
  imports: [PageTitle, Pill, ErrorMessage, RouterLink],
  template: `
    <fs-page-title title="Alert queue" />
    <div class="toolbar" role="toolbar" aria-label="Queue filters">
      <button type="button" class="chip" [class.on]="scope() === 'mine'" [attr.aria-pressed]="scope() === 'mine'" (click)="setScope('mine')">My queue ({{ queue()?.mine ?? 0 }})</button>
      <button type="button" class="chip" [class.on]="scope() === 'team'" [attr.aria-pressed]="scope() === 'team'" (click)="setScope('team')">Team ({{ queue()?.team ?? 0 }})</button>
      <label class="chip select">Channel:
        <select (change)="channel.set($any($event.target).value); load()" aria-label="Channel">
          <option value="">All</option><option value="INSTANT">Instant</option><option value="CARD">Card</option><option value="TRANSFER">Transfer</option>
        </select>
      </label>
      <label class="chip select">Score:
        <select (change)="minScore.set(+$any($event.target).value); load()" aria-label="Minimum score">
          <option value="70">70+</option><option value="80">80+</option><option value="90">90+</option><option value="0">Any</option>
        </select>
      </label>
      <label class="chip select">Amount:
        <select (change)="minAmount.set(+$any($event.target).value); load()" aria-label="Minimum amount">
          <option value="0">Any</option><option value="1000">£1,000+</option><option value="5000">£5,000+</option>
        </select>
      </label>
      <button type="button" class="chip" (click)="assignNext()">Assign next</button>
    </div>
    <fs-error [message]="error()" />
    <section class="card">
      <table class="queue">
        <caption class="sr-only">Alerts ordered by score, then time left before the service level is missed</caption>
        <thead><tr>
          <th scope="col">Score</th><th scope="col">Alert</th><th scope="col">Amount</th><th scope="col">Channel</th>
          <th scope="col">Main reason</th><th scope="col">Decision held</th><th scope="col">SLA</th>
        </tr></thead>
        <tbody>
          @for (a of queue()?.items ?? []; track a.id) {
            <tr class="clickable" (click)="open(a.caseId)">
              <td><fs-pill [tone]="tone(a.score)">{{ a.score }}</fs-pill></td>
              <td><a [routerLink]="['/cases', a.caseId]" (click)="$event.stopPropagation()">{{ a.id }}</a></td>
              <td>{{ money(a.amount, a.currency) }}</td>
              <td>{{ channelLabel(a.channel) }}</td>
              <td>{{ a.mainReason }}</td>
              <td>{{ a.decision }}</td>
              <td [class.urgent]="minutesLeft(a.slaDueAt) < 5">{{ sla(a.slaDueAt) }}</td>
            </tr>
          } @empty {
            <tr><td colspan="7" class="muted">{{ queue() ? 'Nothing waiting. Use Assign next to take the most urgent team alert.' : 'Loading…' }}</td></tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class AlertQueuePage {
  private api = inject(Api);
  private router = inject(Router);
  protected queue = signal<Queue | null>(null);
  protected error = signal<string | null>(null);
  protected scope = signal<'mine' | 'team'>('mine');
  protected channel = signal('');
  protected minScore = signal(70);
  protected minAmount = signal(0);
  protected money = money;
  protected minutesLeft = minutesLeft;

  constructor() {
    autoReload(() => this.load(), 30_000);
  }

  load() {
    this.api.queue(this.scope(), {
      channel: this.channel() || undefined,
      minScore: this.minScore() || undefined,
      minAmount: this.minAmount() || undefined,
    }).subscribe({
      next: (q) => { this.queue.set(q); this.error.set(null); },
      error: (e) => this.error.set(problem(e)),
    });
  }

  setScope(scope: 'mine' | 'team') {
    this.scope.set(scope);
    this.load();
  }

  assignNext() {
    this.api.assignNext().subscribe({
      next: (a) => this.router.navigate(['/cases', a.caseId]),
      error: (e) => this.error.set(problem(e)),
    });
  }

  open(caseId: string) { this.router.navigate(['/cases', caseId]); }
  tone(score: number) { return scoreTone(score); }
  sla(due: string) { return slaText(due); }
  channelLabel(c: string) { return CHANNEL_LABELS[c] ?? c; }
}
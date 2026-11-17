import { Component, computed, inject, signal } from '@angular/core';
import { Api, Overview, problem } from '../api';
import { CHANNEL_LABELS, compact } from '../ui/format';
import { LineChart, Series } from '../ui/line-chart';
import { Bar, ErrorMessage, PageTitle, Pill } from '../ui/page';
import { autoReload } from '../ui/reload';

/** Screen 1: fraud operations overview, for team leads. */
@Component({
  selector: 'fs-overview',
  imports: [PageTitle, Pill, Bar, LineChart, ErrorMessage],
  template: `
    <fs-page-title title="Fraud operations overview" />
    <fs-error [message]="error()" />
    @if (data(); as d) {
      <section class="kpis" aria-label="Key figures">
        <div class="card kpi"><span>Transactions today</span><strong>{{ compact(d.kpis.transactionsToday) }}</strong>
          <small>{{ d.kpis.changeVsLastWeekPct >= 0 ? '+' : '' }}{{ d.kpis.changeVsLastWeekPct }}% vs. same day last week</small></div>
        <div class="card kpi"><span>Held or blocked</span><strong>{{ compact(d.kpis.heldOrBlocked) }}</strong>
          <small>{{ d.kpis.heldOrBlockedPct }}% of volume</small></div>
        <div class="card kpi"><span>Alerts waiting</span><strong>{{ d.kpis.alertsWaiting }}</strong>
          <small>{{ d.kpis.closeToSla }} close to SLA</small></div>
        <div class="card kpi"><span>False-positive ratio</span>
          <strong>{{ d.kpis.falsePositiveRatio == null ? '–' : d.kpis.falsePositiveRatio + ' : 1' }}</strong>
          <small [class.warn]="(d.kpis.falsePositiveRatio ?? 0) >= 8">Target below 8 : 1</small></div>
        <div class="card kpi"><span>Scoring p99</span>
          <strong>{{ d.kpis.scoringP99Ms == null ? '–' : d.kpis.scoringP99Ms + ' ms' }}</strong>
          <small [class.warn]="(d.kpis.scoringP99Ms ?? 0) >= 200">Target below 200 ms</small></div>
      </section>

      <div class="grid two-one">
        <section class="card">
          <h2>Alerts per hour</h2>
          <fs-line-chart [labels]="hours()" [series]="series()" description="Alerts per hour today, compared with the same day last week" />
        </section>
        <section class="card">
          <h2>Top alert reasons</h2>
          @for (r of d.topReasons; track r.reason) {
            <fs-bar [label]="r.reason" [valueText]="r.percent + '%'" [percent]="r.percent / maxReason() * 100" />
          } @empty { <p class="muted">No alerts in the last 7 days.</p> }
        </section>
      </div>

      <div class="grid two-one">
        <section class="card">
          <h2>Queue by channel</h2>
          <table>
            <thead><tr><th scope="col">Channel</th><th scope="col">Waiting</th><th scope="col">Oldest</th><th scope="col">Status</th></tr></thead>
            <tbody>
              @for (q of d.queueByChannel; track q.channel) {
                <tr><td>{{ channel(q.channel) }}</td><td>{{ q.waiting }}</td><td>{{ q.oldestMinutes }} min</td>
                  <td><fs-pill [tone]="q.status === 'On target' ? 'green' : q.status === 'Watch' ? 'amber' : 'red'">{{ q.status }}</fs-pill></td></tr>
              } @empty { <tr><td colspan="4" class="muted">No alerts waiting.</td></tr> }
            </tbody>
          </table>
        </section>
        <section class="card">
          <h2>Platform health</h2>
          <dl class="health">
            <div><dt>Decision API</dt><dd><fs-pill [tone]="d.platformHealth.decisionApi === 'Healthy' ? 'green' : 'red'">{{ d.platformHealth.decisionApi }}</fs-pill></dd></div>
            <div><dt>Feature freshness</dt><dd><fs-pill tone="teal">{{ d.platformHealth.featureFreshnessSeconds == null ? '–' : d.platformHealth.featureFreshnessSeconds + ' s' }}</fs-pill></dd></div>
            <div><dt>Live model</dt><dd><fs-pill tone="teal">{{ d.platformHealth.liveModel ?? '–' }}</fs-pill></dd></div>
            <div><dt>Fallback rules active</dt><dd><fs-pill [tone]="d.platformHealth.fallbackActive ? 'red' : 'grey'">{{ d.platformHealth.fallbackActive ? 'Yes' : 'No' }}</fs-pill></dd></div>
          </dl>
        </section>
      </div>
    } @else if (!error()) { <p class="muted">Loading…</p> }
  `,
})
export class OverviewPage {
  private api = inject(Api);
  protected data = signal<Overview | null>(null);
  protected error = signal<string | null>(null);
  protected compact = compact;
  protected hours = computed(() => (this.data()?.alertsPerHour ?? []).map((p) => String(p.hour).padStart(2, '0')));
  protected series = computed<Series[]>(() => {
    const points = this.data()?.alertsPerHour ?? [];
    return [
      { name: 'Today', values: points.map((p) => p.today), colour: '#c2410c' },
      { name: 'Same day last week', values: points.map((p) => p.lastWeek), colour: '#6b7280', dashed: true },
    ];
  });
  protected maxReason = computed(() => Math.max(1, ...(this.data()?.topReasons ?? []).map((r) => r.percent)));

  constructor() {
    autoReload(() => this.api.overview().subscribe({
      next: (d) => { this.data.set(d); this.error.set(null); },
      error: (e) => this.error.set(problem(e)),
    }), 30_000);
  }

  channel(c: string) { return CHANNEL_LABELS[c] ?? c; }
}
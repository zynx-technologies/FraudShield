import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, Monitoring, problem } from '../api';
import { date, monthYear, titleCase } from '../ui/format';
import { LineChart, Series } from '../ui/line-chart';
import { ErrorMessage, PageTitle, Pill } from '../ui/page';
import { autoReload } from '../ui/reload';

/** Screen 5: model monitoring, for ML engineers and model risk. Compares champion and challenger, and shows drift. */
@Component({
  selector: 'fs-models',
  imports: [PageTitle, Pill, ErrorMessage, LineChart, RouterLink],
  template: `
    <fs-page-title title="Model monitoring" />
    <fs-error [message]="error()" />
    @if (data(); as m) {
      <section class="kpis four" aria-label="Key figures">
        <div class="card kpi"><span>Live model</span><strong>{{ m.champion ? m.model + ' v' + m.champion.version : 'None' }}</strong>
          <small>{{ m.champion?.deployedAt ? 'Deployed ' + date(m.champion!.deployedAt) + ', canary passed' : 'Baseline scoring until a model is released' }}</small></div>
        <div class="card kpi"><span>PR-AUC (labelled, 30 days)</span><strong>{{ m.champion?.livePrAuc ?? '–' }}</strong>
          <small>Baseline {{ m.prAucBaseline ?? '–' }}</small></div>
        <div class="card kpi"><span>Recall at 0.2% alert rate</span><strong>{{ m.champion?.recallPct == null ? '–' : m.champion!.recallPct + '%' }}</strong>
          <small [class.warn]="(m.champion?.recallPct ?? 100) < m.minimumRecallPct">Minimum {{ m.minimumRecallPct }}%</small></div>
        <div class="card kpi"><span>Data drift alerts</span><strong>{{ m.driftAlerts }}</strong>
          <small>{{ m.driftAlertFeatures.join(', ') || 'None' }}</small></div>
      </section>

      <div class="grid half">
        <section class="card">
          <h2>Champion vs. challenger: recall at fixed alert rate</h2>
          <fs-line-chart [labels]="weeks()" [series]="series()" [yMin]="70" [yMax]="85" ySuffix="%"
            description="Weekly recall of the champion and the challenger in shadow" />
        </section>
        <section class="card">
          <h2>Feature drift</h2>
          <table>
            <thead><tr><th scope="col">Feature</th><th scope="col">PSI</th><th scope="col">Status</th></tr></thead>
            <tbody>
              @for (d of m.drift; track d.feature) {
                <tr><td><code>{{ d.feature }}</code></td><td>{{ d.psi.toFixed(2) }}</td>
                  <td><fs-pill [tone]="d.status === 'Alert' ? 'red' : d.status === 'Watch' ? 'amber' : 'green'">{{ d.status }}</fs-pill></td></tr>
              } @empty { <tr><td colspan="3" class="muted">No drift measurements yet.</td></tr> }
            </tbody>
          </table>
        </section>
      </div>

      <section class="card">
        <h2>Model versions</h2>
        <table>
          <thead><tr><th scope="col">Version</th><th scope="col">Stage</th><th scope="col">Training window</th><th scope="col">PR-AUC</th><th scope="col">Latency p99</th><th scope="col">Owner</th></tr></thead>
          <tbody>
            @for (v of m.versions; track v.version) {
              <tr><td>{{ m.model }} v{{ v.version }}</td>
                <td><fs-pill [tone]="v.stage === 'CHAMPION' ? 'green' : v.stage === 'SHADOW' ? 'amber' : 'grey'">{{ titleCase(v.stage) }}</fs-pill></td>
                <td>{{ monthYear(v.trainingFrom) }} to {{ monthYear(v.trainingTo) }}</td>
                <td>{{ v.prAuc ?? '–' }}</td><td>{{ v.latencyP99Ms == null ? '–' : v.latencyP99Ms + ' ms' }}</td><td>{{ v.owner }}</td></tr>
            } @empty { <tr><td colspan="6" class="muted">No versions registered.</td></tr> }
          </tbody>
        </table>
        @if (m.pendingRelease) {
          <p><a [routerLink]="['/releases', m.model, m.pendingRelease]">Review the release request for v{{ m.pendingRelease }}</a></p>
        }
      </section>
    } @else if (!error()) { <p class="muted">Loading…</p> }
  `,
})
export class ModelsPage {
  private api = inject(Api);
  protected data = signal<Monitoring | null>(null);
  protected error = signal<string | null>(null);
  protected date = date;
  protected monthYear = monthYear;
  protected titleCase = titleCase;
  protected weeks = computed(() => (this.data()?.weekly ?? []).map((_, i) => `Wk ${i + 1}`));
  protected series = computed<Series[]>(() => {
    const m = this.data();
    if (!m) return [];
    const s: Series[] = [{ name: `Champion v${m.champion?.version ?? ''}`, values: m.weekly.map((w) => w.champion), colour: '#1c2a4a' }];
    if (m.challenger) s.push({ name: `Challenger v${m.challenger.version} (shadow)`, values: m.weekly.map((w) => w.challenger), colour: '#c2410c', dashed: true });
    return s;
  });

  constructor() {
    autoReload(() => this.api.monitoring('fs-txn').subscribe({
      next: (m) => { this.data.set(m); this.error.set(null); },
      error: (e) => this.error.set(problem(e)),
    }));
  }
}
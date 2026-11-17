import { Component, inject } from '@angular/core';
import { APP_SETTINGS } from '../settings';
import { PageTitle } from '../ui/page';

/** Fraud KPIs, value saved, and rule performance are Power BI reports over Databricks SQL (design section 13). */
@Component({
  selector: 'fs-reports',
  imports: [PageTitle],
  template: `
    <fs-page-title title="Reports" />
    <section class="card">
      <h2>Fraud and model reports</h2>
      <p>Fraud losses, value saved, customer complaints, and rule performance are reported in Power BI, from the
        lakehouse Gold layer through Databricks SQL. They are reviewed at the monthly governance meeting.</p>
      @if (url) {
        <p><a [href]="url" target="_blank" rel="noopener">Open the FraudShield reports in Power BI</a></p>
      } @else {
        <p class="muted">The Power BI workspace is not linked in this environment.</p>
      }
    </section>
  `,
})
export class ReportsPage {
  protected url = inject(APP_SETTINGS).reportsUrl;
}
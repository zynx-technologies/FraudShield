import { Component, inject, input } from '@angular/core';
import { APP_SETTINGS } from '../settings';

/** Page title with the environment's data label, as on every mock-up. */
@Component({
  selector: 'fs-page-title',
  template: `
    <div class="page-title">
      <h1>{{ title() }}</h1>
      <ng-content />
      @if (label) { <span class="data-label">{{ label }}</span> }
    </div>
  `,
})
export class PageTitle {
  title = input.required<string>();
  protected label = inject(APP_SETTINGS).dataLabel;
}

/** A coloured status label. */
@Component({
  selector: 'fs-pill',
  template: `<span class="pill" [class]="'pill ' + tone()"><ng-content /></span>`,
})
export class Pill {
  tone = input<'red' | 'amber' | 'green' | 'grey' | 'teal' | string>('grey');
}

/** A labelled horizontal bar, used for reasons and threshold sliders. */
@Component({
  selector: 'fs-bar',
  template: `
    <div class="bar-row">
      <div class="bar-label"><span>{{ label() }}</span><strong>{{ valueText() }}</strong></div>
      <div class="bar-track" role="presentation"><div class="bar-fill" [style.width.%]="percent()"></div></div>
    </div>
  `,
})
export class Bar {
  label = input.required<string>();
  valueText = input.required<string>();
  percent = input.required<number>();
}

/** Shown when a request fails, including permission errors. */
@Component({
  selector: 'fs-error',
  template: `@if (message()) { <p class="error" role="alert">{{ message() }}</p> }`,
})
export class ErrorMessage {
  message = input<string | null>(null);
}
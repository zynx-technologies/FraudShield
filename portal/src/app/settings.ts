import { InjectionToken } from '@angular/core';

export interface AppSettings {
  authMode: 'dev' | 'entra';
  clientId?: string;
  tenantId?: string;
  apiScope?: string;
  /** Shown next to each page title, for example "Synthetic demo data". Empty in production. */
  dataLabel?: string;
  /** Power BI workspace for fraud and model reports. */
  reportsUrl?: string;
}

export const APP_SETTINGS = new InjectionToken<AppSettings>('APP_SETTINGS');
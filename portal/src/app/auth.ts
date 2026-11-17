import { HttpInterceptorFn } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { from, switchMap } from 'rxjs';
import { PublicClientApplication } from '@azure/msal-browser';
import { APP_SETTINGS } from './settings';

/** Local development only: sign-in is replaced by a person picker that sends X-Dev headers. */
export const DEV_USERS = [
  { user: 'j.analyst@example.test', roles: 'ANALYST,TEAM_LEAD', label: 'J. Analyst', team: 'Fraud Operations' },
  { user: 's.strategist@example.test', roles: 'STRATEGIST', label: 'S. Strategist', team: 'Fraud Strategy' },
  { user: 'r.approver@example.test', roles: 'RISK_APPROVER,STRATEGIST', label: 'R. Approver', team: 'Fraud Risk' },
  { user: 'm.engineer@example.test', roles: 'ML_ENGINEER', label: 'M. Engineer', team: 'ML Engineering' },
  { user: 'k.riskowner@example.test', roles: 'MODEL_RISK_OWNER,TEAM_LEAD', label: 'K. Risk Owner', team: 'Model Risk' },
];

@Injectable({ providedIn: 'root' })
export class AuthService {
  private settings = inject(APP_SETTINGS);
  private msal?: PublicClientApplication;

  readonly isDev = this.settings.authMode === 'dev';
  readonly devUser = signal(DEV_USERS[0]);
  readonly displayName = signal('');
  readonly roles = signal<string[]>([]);

  /** Signs in before the first screen appears. In Entra mode this redirects if there is no session. */
  async initialise(): Promise<void> {
    if (this.isDev) return;
    this.msal = new PublicClientApplication({
      auth: {
        clientId: this.settings.clientId!,
        authority: `https://login.microsoftonline.com/${this.settings.tenantId}`,
        redirectUri: window.location.origin,
      },
      cache: { cacheLocation: 'sessionStorage' },
    });
    await this.msal.initialize();
    const result = await this.msal.handleRedirectPromise();
    const account = result?.account ?? this.msal.getAllAccounts()[0];
    if (!account) {
      await this.msal.loginRedirect({ scopes: [this.settings.apiScope!] });
      return;
    }
    this.msal.setActiveAccount(account);
    this.displayName.set(account.name ?? account.username);
    this.roles.set((account.idTokenClaims?.['roles'] as string[] | undefined) ?? []);
  }

  name(): string {
    return this.isDev ? this.devUser().label : this.displayName();
  }

  team(): string {
    return this.isDev ? this.devUser().team : 'Fraud Operations';
  }

  userId(): string {
    return this.isDev ? this.devUser().user : (this.msal?.getActiveAccount()?.username ?? '');
  }

  hasRole(role: string): boolean {
    return this.isDev ? this.devUser().roles.split(',').includes(role) : this.roles().includes(role);
  }

  async token(): Promise<string> {
    const scopes = [this.settings.apiScope!];
    try {
      return (await this.msal!.acquireTokenSilent({ scopes })).accessToken;
    } catch (error) {
      await this.msal!.acquireTokenRedirect({ scopes });
      throw error;
    }
  }
}

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/')) return next(request);
  const auth = inject(AuthService);
  if (auth.isDev) {
    const dev = auth.devUser();
    return next(request.clone({ setHeaders: { 'X-Dev-User': dev.user, 'X-Dev-Roles': dev.roles } }));
  }
  return from(auth.token()).pipe(
    switchMap((token) => next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }))),
  );
};
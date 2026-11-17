import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService, DEV_USERS } from './auth';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <a class="skip" href="#main">Skip to content</a>
    <div class="shell">
      <nav class="sidebar" aria-label="Main">
        <div class="brand">FraudShield</div>
        @for (item of nav; track item.path) {
          <a [routerLink]="item.path" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: false }">{{ item.label }}</a>
        }
      </nav>
      <div class="content">
        <header class="topbar">
          <span class="who">
            @if (auth.isDev) {
              <label class="sr-only" for="dev-user">Signed in as (local development)</label>
              <select id="dev-user" (change)="pick($any($event.target).value)">
                @for (u of devUsers; track u.user) {
                  <option [value]="u.user" [selected]="u.user === auth.devUser().user">{{ u.label }} · {{ u.team }}</option>
                }
              </select>
            } @else {
              {{ auth.name() }} · {{ auth.team() }}
            }
          </span>
        </header>
        <main id="main" tabindex="-1">
          <router-outlet />
        </main>
      </div>
    </div>
  `,
})
export class App {
  protected auth = inject(AuthService);
  protected devUsers = DEV_USERS;
  protected nav = [
    { path: '/overview', label: 'Overview' },
    { path: '/alerts', label: 'Alert queue' },
    { path: '/cases', label: 'Cases' },
    { path: '/rules', label: 'Rules' },
    { path: '/models', label: 'Models' },
    { path: '/releases', label: 'Release approval' },
    { path: '/reports', label: 'Reports' },
  ];

  pick(user: string) {
    const found = DEV_USERS.find((u) => u.user === user);
    if (found) {
      this.auth.devUser.set(found);
      // Reload the current screen with the new person's permissions.
      window.dispatchEvent(new Event('fs-user-changed'));
    }
  }
}
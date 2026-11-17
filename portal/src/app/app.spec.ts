import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { authInterceptor, AuthService, DEV_USERS } from './auth';
import { APP_SETTINGS } from './settings';
import { Api, CaseView, Queue, Release } from './api';
import { AlertQueuePage } from './pages/alert-queue';
import { CaseInvestigationPage } from './pages/case-investigation';
import { ReleaseApprovalPage } from './pages/release-approval';
import { compact, money, scoreTone, slaText } from './ui/format';

const settings = { provide: APP_SETTINGS, useValue: { authMode: 'dev', dataLabel: 'Synthetic demo data' } };

function setup(api: Partial<Api>) {
  TestBed.configureTestingModule({
    providers: [provideZonelessChangeDetection(), provideRouter([]), settings, { provide: Api, useValue: api }],
  });
}

async function settle(fixture: { whenStable: () => Promise<unknown>; detectChanges: () => void }) {
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
}

describe('formatting', () => {
  it('shows pounds, compact volumes, score tones, and time left', () => {
    expect(money(9850, 'GBP')).toBe('£9,850.00');
    expect(compact(1_240_000)).toBe('1.24M');
    expect(compact(1872)).toBe('1,872');
    expect([scoreTone(98), scoreTone(84), scoreTone(74)]).toEqual(['red', 'amber', 'grey']);
    expect(slaText(new Date(Date.now() + 2 * 60000 + 1000).toISOString())).toBe('2 min left');
  });
});

describe('auth interceptor', () => {
  it('adds the local identity to API calls only', () => {
    TestBed.configureTestingModule({
      providers: [settings, provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpClient);
    const mock = TestBed.inject(HttpTestingController);
    http.get('/api/v1/alerts').subscribe();
    http.get('/config.json').subscribe();
    const api = mock.expectOne('/api/v1/alerts').request;
    expect(api.headers.get('X-Dev-User')).toBe('j.analyst@example.test');
    expect(api.headers.get('X-Dev-Roles')).toBe('ANALYST,TEAM_LEAD');
    expect(mock.expectOne('/config.json').request.headers.has('X-Dev-User')).toBe(false);
  });
});

describe('alert queue (screen 2)', () => {
  const queue: Queue = {
    mine: 2, team: 46,
    items: [
      { id: 'AL-90412', caseId: 'CS-20417', score: 98, amount: 9850, currency: 'GBP', channel: 'INSTANT', mainReason: 'New payee and high amount',
        decision: 'Held', createdAt: new Date().toISOString(), slaDueAt: new Date(Date.now() + 2 * 60000 + 1000).toISOString(), assignedTo: 'j' },
      { id: 'AL-90349', caseId: 'CS-20409', score: 74, amount: 5000, currency: 'GBP', channel: 'TRANSFER', mainReason: 'New payee',
        decision: 'Step-up sent', createdAt: new Date().toISOString(), slaDueAt: new Date(Date.now() + 47 * 60000 + 1000).toISOString(), assignedTo: 'j' },
    ],
  };

  it('shows counts, scores, amounts, and time left, and assigns the next alert', async () => {
    const assignNext = vi.fn(() => of({ ...queue.items[0] }));
    setup({ queue: () => of(queue), assignNext });
    const fixture = TestBed.createComponent(AlertQueuePage);
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('My queue (2)');
    expect(el.textContent).toContain('Team (46)');
    const rows = el.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('£9,850.00');
    expect(rows[0].textContent).toContain('2 min left');
    expect(rows[0].querySelector('.pill')!.className).toContain('red');
    expect(rows[1].querySelector('.pill')!.className).toContain('grey');

    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Assign next'))!.click();
    expect(assignNext).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/cases', 'CS-20417']);
  });
});

describe('case investigation (screen 3)', () => {
  const view: CaseView = {
    id: 'CS-20417', status: 'OPEN', assignedTo: 'j.analyst@example.test', score: 98,
    transaction: {
      alertId: 'AL-90412', decisionId: 'DE-55291', transactionId: 'TX-77120', channel: 'INSTANT', amount: 9850, currency: 'GBP',
      accountMasked: '****8fa1', payee: 'tok_21bd', payeeFirstPayment: true, deviceId: 'D-55', deviceFirstSeenToday: true,
      decision: 'Held', latencyMs: 148, usualMedian: 210, amountToMedian: 46.9, modelVersion: 'fs-baseline-1', rulesetVersion: 'rs-203',
      decidedAt: new Date().toISOString(),
    },
    reasons: [{ code: 'NEW_PAYEE_HIGH_AMOUNT', label: 'New payee and high amount', value: 0.31 }],
    ruleHits: [], alerts: [], history: [{ at: new Date().toISOString(), actor: 'system', type: 'ALERT_CREATED', detail: 'Alert created' }],
    links: { nodes: [{ id: 'tok_8fa1', label: '****8fa1', type: 'ACCOUNT', flagged: false, focus: true }], edges: [] },
    outcome: null,
  };

  async function create(act = vi.fn(() => of({ ...view, status: 'CLOSED' }))) {
    const summary = vi.fn(() => of({
      caseId: 'CS-20417', removed: 0, generator: 'extractive', notice: 'AI draft only. It does not make or recommend a final decision.',
      statements: [{ text: 'The customer made a first payment of £9,850.00 to a new payee.', sources: ['TX-77120'] }],
      sources: [{ id: 'TX-77120', label: 'transaction TX-77120' }, { id: 'DL-D-55', label: 'device log D-55' }],
    }));
    setup({ case: () => of(view), act, summary });
    const fixture = TestBed.createComponent(CaseInvestigationPage);
    fixture.componentRef.setInput('id', 'CS-20417');
    await settle(fixture);
    return { fixture, act, summary, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the transaction and model reasons from the design', async () => {
    const { el } = await create();
    expect(el.textContent).toContain('Case CS-20417');
    expect(el.textContent).toContain('£9,850.00 instant payment');
    expect(el.textContent).toContain('****8fa1 (masked)');
    expect(el.textContent).toContain('tok_21bd, first payment');
    expect(el.textContent).toContain('£210 median, 90 days');
    expect(el.textContent).toContain('+0.31');
    expect(el.textContent).toContain('Shows model drivers, not proof of fraud');
  });

  it('needs a reason before any action, and sends it', async () => {
    const { fixture, el, act } = await create();
    const confirm = [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Confirm fraud'))!;
    expect(confirm.disabled).toBe(true);
    const reason = el.querySelector<HTMLTextAreaElement>('#reason')!;
    reason.value = 'Customer did not make this payment';
    reason.dispatchEvent(new Event('input'));
    await settle(fixture);
    expect(confirm.disabled).toBe(false);
    confirm.click();
    expect(act).toHaveBeenCalledWith('CS-20417', 'CONFIRM_FRAUD', 'Customer did not make this payment');
  });

  it('labels the AI summary as a draft and lists only the sources it cites', async () => {
    const { fixture, el, summary } = await create();
    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Draft summary'))!.click();
    await settle(fixture);
    expect(summary).toHaveBeenCalledWith('CS-20417');
    expect(el.textContent).toContain('Sources: transaction TX-77120');
    expect(el.textContent).not.toContain('device log D-55');
    expect(el.textContent).toContain('does not make or recommend a final decision');
  });
});

describe('release approval (screen 6)', () => {
  const release: Release = {
    id: 1, model: 'fs-txn', version: 15, requestedBy: 'ml.engineering@example.test', requestedAt: new Date().toISOString(),
    status: 'PENDING', rollbackTo: 14, modelCardUrl: 'https://example.test/card', lineageUrl: null, trainedBy: 'm.engineer@example.test',
    decidedBy: null, decidedAt: null, decisionReason: null, readyForDecision: true,
    gates: [{ gate: 'Shadow run, 14 days', result: 'Stable', status: 'PASSED' }, { gate: 'Risk owner approval', result: 'Awaiting decision', status: 'PENDING' }],
  };

  async function create(user: (typeof DEV_USERS)[number]) {
    const decideRelease = vi.fn(() => of({ ...release, status: 'APPROVED' }));
    setup({ release: () => of(release), decideRelease });
    TestBed.inject(AuthService).devUser.set(user);
    const fixture = TestBed.createComponent(ReleaseApprovalPage);
    fixture.componentRef.setInput('model', 'fs-txn');
    fixture.componentRef.setInput('version', '15');
    await settle(fixture);
    return { fixture, decideRelease, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the gate evidence and lets the model risk owner approve with a reason', async () => {
    const { fixture, el, decideRelease } = await create(DEV_USERS.find((u) => u.roles.includes('MODEL_RISK_OWNER'))!);
    expect(el.textContent).toContain('Release approval: fs-txn v15');
    expect(el.textContent).toContain('Automatic, to v14');
    const approve = [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Approve canary'))!;
    expect(approve.disabled).toBe(true);
    const input = el.querySelector<HTMLInputElement>('#release-reason')!;
    input.value = 'Gate evidence reviewed';
    input.dispatchEvent(new Event('input'));
    await settle(fixture);
    approve.click();
    expect(decideRelease).toHaveBeenCalledWith('fs-txn', 15, true, 'Gate evidence reviewed');
  });

  it('does not offer approval to the person who trained the model', async () => {
    const { el } = await create(DEV_USERS.find((u) => u.user === 'm.engineer@example.test')!);
    expect([...el.querySelectorAll('button')].some((b) => b.textContent?.includes('Approve canary'))).toBe(false);
  });
});

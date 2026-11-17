import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';

// ---------- Case service ----------
export interface Kpis {
  transactionsToday: number; changeVsLastWeekPct: number; heldOrBlocked: number; heldOrBlockedPct: number;
  alertsWaiting: number; closeToSla: number; falsePositiveRatio: number | null; scoringP99Ms: number | null;
}
export interface Overview {
  kpis: Kpis;
  alertsPerHour: { hour: number; today: number; lastWeek: number }[];
  topReasons: { reason: string; percent: number }[];
  queueByChannel: { channel: string; waiting: number; oldestMinutes: number; status: string }[];
  platformHealth: { decisionApi: string; featureFreshnessSeconds?: number; liveModel?: string; fallbackActive?: boolean; scoringMode?: string; rulesetVersion?: string };
  asOf: string;
}
export interface QueueItem {
  id: string; caseId: string; score: number; amount: number; currency: string; channel: string; mainReason: string;
  decision: string; createdAt: string; slaDueAt: string; assignedTo: string | null;
}
export interface Queue { items: QueueItem[]; mine: number; team: number; }
export interface CaseListItem {
  id: string; status: string; assignedTo: string | null; score: number; amount: number; currency: string; channel: string;
  mainReason: string; createdAt: string; outcome: string | null;
}
export interface CaseView {
  id: string; status: string; assignedTo: string | null; score: number;
  transaction: {
    alertId: string; decisionId: string; transactionId: string; channel: string; amount: number; currency: string;
    accountMasked: string; payee: string | null; payeeFirstPayment: boolean; deviceId: string | null; deviceFirstSeenToday: boolean;
    decision: string; latencyMs: number | null; usualMedian: number | null; amountToMedian: number | null;
    modelVersion: string | null; rulesetVersion: string | null; decidedAt: string;
  };
  reasons: { code: string; label: string; value: number }[];
  ruleHits: { ruleId: string; name: string; action: string; live: boolean }[];
  alerts: { id: string; score: number; amount: number; currency: string; channel: string; mainReason: string; decision: string; status: string }[];
  history: { at: string; actor: string; type: string; detail: string }[];
  links: { nodes: { id: string; label: string; type: string; flagged: boolean; focus: boolean }[]; edges: { from: string; to: string; relation: string }[] };
  outcome: { label: string; reason: string; decidedBy: string; decidedAt: string } | null;
}
export type CaseAction = 'CONFIRM_FRAUD' | 'MARK_GENUINE' | 'CALL_CUSTOMER' | 'ESCALATE_AML';

// ---------- Copilot ----------
export interface Summary {
  caseId: string; statements: { text: string; sources: string[] }[]; sources: { id: string; label: string }[];
  removed: number; generator: string; notice: string;
}

// ---------- Strategy service ----------
export interface Rule {
  id: string; name: string; type: string; action: string; status: string; params: Record<string, string>;
  version: number | null; hits7d: number; precisionPct: number | null;
  pending: { version: number; action: string; mode: string; params: Record<string, string>; author: string; createdAt: string } | null;
}
export interface RuleType { type: string; description: string; params: { name: string; label: string; kind: string }[]; }
export interface RuleChange {
  ruleId: string; ruleName: string; version: number; action: string; mode: string; author: string; createdAt: string;
  status: string; decidedBy: string | null; decidedAt: string | null; decisionReason: string | null;
}
export interface Thresholds { stepUp: number; hold: number; block: number; version?: string; }
export interface Policy { id: number; stepUp: number; hold: number; block: number; status: string; reason: string; author: string; createdAt: string; decidedBy: string | null; }
export interface Metrics { fraudValueStopped: number; fraudRecallPct: number; alertsPerDay: number; falsePositiveRatio: number | null; stepUpsPerDay: number; blocksPerDay: number; }
export interface Simulation { windowDays: number; refreshedAt: string; current: Metrics; proposed: Metrics; }
export interface ModelVersion {
  version: number; stage: string; trainingFrom: string | null; trainingTo: string | null; prAuc: number | null; livePrAuc: number | null;
  recallPct: number | null; latencyP99Ms: number | null; owner: string; trainedBy: string; deployedAt: string | null;
}
export interface Monitoring {
  model: string; champion: ModelVersion | null; challenger: ModelVersion | null; prAucBaseline: number | null; minimumRecallPct: number;
  driftAlerts: number; driftAlertFeatures: string[]; weekly: { week: string; champion: number | null; challenger: number | null }[];
  drift: { feature: string; psi: number; status: string }[]; versions: ModelVersion[]; pendingRelease: number | null;
}
export interface Release {
  id: number; model: string; version: number; requestedBy: string; requestedAt: string; status: string; rollbackTo: number | null;
  modelCardUrl: string | null; lineageUrl: string | null; trainedBy: string | null; decidedBy: string | null; decidedAt: string | null;
  decisionReason: string | null; gates: { gate: string; result: string; status: string }[]; readyForDecision: boolean;
}

/** Turns an API error into a sentence for the screen. Never shows raw server output. */
export function problem(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 403) return error.error?.detail ?? 'Your role cannot do this.';
    if (error.status === 0) return 'The service cannot be reached. Try again shortly.';
    return error.error?.detail ?? 'Something went wrong. Try again, or contact the service desk.';
  }
  return 'Something went wrong. Try again, or contact the service desk.';
}

@Injectable({ providedIn: 'root' })
export class Api {
  private http = inject(HttpClient);
  private base = '/api/v1';

  overview() { return this.http.get<Overview>(`${this.base}/overview`); }
  queue(scope: string, filters: { channel?: string; minScore?: number; minAmount?: number }) {
    const params: Record<string, string> = { scope };
    if (filters.channel) params['channel'] = filters.channel;
    if (filters.minScore != null) params['minScore'] = String(filters.minScore);
    if (filters.minAmount != null) params['minAmount'] = String(filters.minAmount);
    return this.http.get<Queue>(`${this.base}/alerts`, { params });
  }
  assignNext() { return this.http.post<QueueItem>(`${this.base}/alerts/assign-next`, {}); }
  cases(status: string) { return this.http.get<CaseListItem[]>(`${this.base}/cases`, { params: { status } }); }
  case(id: string) { return this.http.get<CaseView>(`${this.base}/cases/${encodeURIComponent(id)}`); }
  act(id: string, action: CaseAction, reason: string) {
    return this.http.post<CaseView>(`${this.base}/cases/${encodeURIComponent(id)}/actions`, { action, reason });
  }
  summary(caseId: string) { return this.http.post<Summary>(`${this.base}/copilot/cases/${encodeURIComponent(caseId)}/summary`, {}); }

  rules() { return this.http.get<Rule[]>(`${this.base}/rules`); }
  ruleTypes() { return this.http.get<RuleType[]>(`${this.base}/rules/types`); }
  ruleHistory() { return this.http.get<RuleChange[]>(`${this.base}/rules/history`); }
  createRule(rule: { name: string; type: string; action: string; mode: string; params: Record<string, string> }) {
    return this.http.post<Rule>(`${this.base}/rules`, rule);
  }
  decideRule(id: string, version: number, approve: boolean, reason: string) {
    return this.http.post<void>(`${this.base}/rules/${id}/versions/${version}/${approve ? 'approve' : 'reject'}`, { reason });
  }
  backtest(id: string) { return this.http.post<void>(`${this.base}/rules/${id}/backtest`, {}); }
  thresholds() { return this.http.get<{ active: Thresholds; pending: Policy | null; history: Policy[] }>(`${this.base}/thresholds`); }
  simulate(t: Thresholds) { return this.http.post<Simulation>(`${this.base}/thresholds/simulate`, t); }
  proposeThresholds(t: Thresholds, reason: string) {
    return this.http.post<Policy>(`${this.base}/thresholds/proposals`, { ...t, reason });
  }
  decideThresholds(id: number, approve: boolean, reason: string) {
    return this.http.post<Policy>(`${this.base}/thresholds/proposals/${id}/${approve ? 'approve' : 'reject'}`, { reason });
  }
  monitoring(model: string) { return this.http.get<Monitoring>(`${this.base}/models/${model}`); }
  release(model: string, version: number) { return this.http.get<Release>(`${this.base}/models/${model}/releases/${version}`); }
  decideRelease(model: string, version: number, approve: boolean, reason: string) {
    return this.http.post<Release>(`${this.base}/models/${model}/releases/${version}/${approve ? 'approve' : 'reject'}`, { reason });
  }
}
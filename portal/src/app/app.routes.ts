import { Routes } from '@angular/router';

// Feature areas load lazily, so the first screen stays small (design section 10).
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'overview' },
  { path: 'overview', title: 'Overview', loadComponent: () => import('./pages/overview').then((m) => m.OverviewPage) },
  { path: 'alerts', title: 'Alert queue', loadComponent: () => import('./pages/alert-queue').then((m) => m.AlertQueuePage) },
  { path: 'cases', title: 'Cases', loadComponent: () => import('./pages/cases').then((m) => m.CasesPage) },
  { path: 'cases/:id', title: 'Case', loadComponent: () => import('./pages/case-investigation').then((m) => m.CaseInvestigationPage) },
  { path: 'rules', title: 'Rules', loadComponent: () => import('./pages/rules').then((m) => m.RulesPage) },
  { path: 'models', title: 'Models', loadComponent: () => import('./pages/models').then((m) => m.ModelsPage) },
  { path: 'releases', title: 'Release approval', loadComponent: () => import('./pages/release-approval').then((m) => m.ReleaseApprovalPage) },
  { path: 'releases/:model/:version', title: 'Release approval', loadComponent: () => import('./pages/release-approval').then((m) => m.ReleaseApprovalPage) },
  { path: 'reports', title: 'Reports', loadComponent: () => import('./pages/reports').then((m) => m.ReportsPage) },
  { path: '**', redirectTo: 'overview' },
];
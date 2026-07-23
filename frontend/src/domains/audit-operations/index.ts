/** Public entry for the audit-operations frontend domain. Internal files must not be imported cross-domain. */
export {
  AuditSearchClient,
  AuditSearchMemoryState,
  clearAuditSearchIdentityBoundary,
} from './internal/audit-search';
export { AuditRetentionEvidenceClient } from './internal/retention-evidence';
export type { RetentionEvidence } from './internal/retention-evidence';
export type {
  AuditSearchItem,
  AuditSearchRequest,
  AuditSearchResponse,
  AuditSearchViewName,
  SensitiveClearReason,
} from './internal/audit-search';

export const auditOperationsRouteContribution = Object.freeze({
  domain: 'audit-operations',
  routes: Object.freeze([{
    path: '/audit/search',
    name: 'audit-search',
    component: () => import('./internal/AuditSearchView.vue'),
    meta: { requiresIdentity: true },
  }]),
});

/** Public entry for the subject-registry frontend domain. Internal files must not be imported cross-domain. */
export {
  SubjectMappingClient,
  SubjectRegistryResponseFailure,
  SubjectRepairMemory,
  clearSubjectRegistryIdentityBoundary,
  hasUsableSubjectRegistryAuthorization,
  sameSubjectRegistryIdentityGeneration,
  subjectMappingQueryOptions,
} from './internal/subject-mapping';
export type {
  RepairReasonCode,
  SubjectMappingExceptionDetail,
  SubjectMappingExceptionItem,
  SubjectMappingExceptionPage,
  SubjectMappingExceptionStatus,
  SubjectRecomputeJob,
  SubjectRegistryClearReason,
  SubjectRegistryIdentityGeneration,
  SubjectRelationType,
  SubjectRepairCommand,
  SubjectRepairDraft,
  SubjectRepairResult,
} from './internal/subject-mapping';

export const subjectRegistryRouteContribution = Object.freeze({
  domain: 'subject-registry',
  routes: Object.freeze([
    {
      path: '/data-quality/subject-mapping-exceptions',
      name: 'subject-mapping-exceptions',
      component: () => import('./internal/SubjectMappingExceptionsView.vue'),
      meta: { requiresIdentity: true },
    },
    {
      path: '/subject-recompute-jobs',
      name: 'subject-recompute-jobs',
      component: () => import('./internal/SubjectRecomputeJobView.vue'),
      meta: { requiresIdentity: true },
    },
  ]),
});

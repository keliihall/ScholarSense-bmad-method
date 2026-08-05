/** Public entry for ingestion-quality; cross-domain callers must not import internal files. */
export {
  DataSourceCatalogClient,
  DataSourceCatalogMemoryState,
  DataSourceCatalogPublicationMemory,
  DataCatalogResponseFailure,
  FROZEN_DATA_DEPENDENCIES,
  FROZEN_DATA_SOURCE_IDS,
  clearDataCatalogIdentityBoundary,
  dataCatalogQueryOptions,
  hasUsableDataCatalogAuthorization,
  retainPublicationProof,
  sameDataCatalogIdentityGeneration,
} from './internal/data-source-catalog';
export type {
  CatalogPublicationCommand,
  CatalogDetail,
  CatalogPage,
  CatalogSummary,
  DataCatalogClearReason,
  DataCatalogIdentityGeneration,
  SourceContractView,
} from './internal/data-source-catalog';

export const ingestionQualityRouteContribution = Object.freeze({
  domain: 'ingestion-quality',
  routes: Object.freeze([{
    path: '/data-quality/catalogs',
    name: 'data-quality-catalogs',
    component: () => import('./internal/DataSourceCatalogView.vue'),
    meta: { requiresIdentity: true },
  }]),
});

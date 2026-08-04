/** Public entry for ingestion-quality; cross-domain callers must not import internal files. */
export {
  DataSourceCatalogClient,
  DataSourceCatalogMemoryState,
  clearDataCatalogIdentityBoundary,
  dataCatalogQueryOptions,
  hasUsableDataCatalogAuthorization,
} from './internal/data-source-catalog';
export type {
  CatalogDetail,
  CatalogPage,
  CatalogSummary,
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

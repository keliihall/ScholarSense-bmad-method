export type RetentionEvidence = Readonly<{ fields: Readonly<Record<string, unknown>> }>;

const approvedFields = new Set([
  'executionId', 'scheduleVersion', 'state', 'nonProductionEvidence', 'actorDisplayRef',
  'scopeType', 'fixtureId', 'action', 'asOfSequence', 'sourceLedgerHead', 'projectionWatermark',
  'archiveDigest', 'trustedAt', 'traceId', 'unmetGuards', 'steps',
]);

export class AuditRetentionEvidenceClient {
  public constructor(private readonly request: typeof fetch = (input, init) => globalThis.fetch(input, init)) {}

  public async read(
    executionId: string,
    view: 'business' | 'technical',
    signal?: AbortSignal,
  ): Promise<RetentionEvidence> {
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(executionId)) {
      throw new Error('AUDIT_EVIDENCE_NOT_AVAILABLE');
    }
    const response = await this.request(
      `/api/v1/audit-retention-executions/${encodeURIComponent(executionId)}?view=${view}`,
      { credentials: 'include', cache: 'no-store', referrerPolicy: 'no-referrer', signal,
        headers: { Accept: 'application/json' } },
    );
    if (!response.ok) throw new Error(response.status === 403
      ? 'AUDIT_EVIDENCE_NOT_AVAILABLE' : 'AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE');
    const value: unknown = await response.json();
    if (!isRecord(value) || !isRecord(value.fields)
      || Object.keys(value.fields).some((field) => !approvedFields.has(field))) {
      throw new Error('AUDIT_SEARCH_RESPONSE_INVALID');
    }
    return Object.freeze({ fields: Object.freeze({ ...value.fields }) });
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

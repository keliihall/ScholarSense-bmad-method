# audit-operations internals

Files placed here are private to this domain. Other domains import only the parent `index.ts` public entry.

Story 1.5 adds a deep-link-only `audit.search` route. Raw actor/object filters live only in
`AuditSearchMemoryState`; they are never router/query-cache/storage keys. The client fetches the existing
identity CSRF proof before every POST, accepts only the frozen response-field allowlist, and renders the
server projection without client-side role expansion. There are intentionally no export, archive, destroy,
or direct object-link commands.

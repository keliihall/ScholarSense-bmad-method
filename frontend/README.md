# ScholarSense production frontend baseline

Story 1.1c freezes the exact PAB dependency set, production lock, `/scholarsense/` base path, build budgets, performance sampling contracts and deterministic Playwright/axe fixture. The current page is a compatibility surface, not a business page or a formal portal/mobile acceptance report.

Run the exact toolchain from the repository root:

```bash
./scripts/bootstrap.sh
./scripts/verify.sh
```

For frontend-only development, invoke commands through `_bmad/scripts/with_pab_toolchain.sh` and keep generated trees outside the source checkout. `scripts/verify_frontend.sh` does this automatically. Brand-browser preflight is intentionally separate and must run in a disposable full-project workspace with exact dependencies already installed. Before reading an environment record, it requires the repository's fixed `scripts/check_frontend_baseline.py` oracle to pass, so editing the manifest and recomputing its self-digest cannot create an approved target. It then reads the approved version, target OS, immutable artifact URL/digest and optional executable digest from `contracts/performance/test-environment-1.0.0.json`; callers cannot supply their own expected version. `--artifact` names the downloaded zip/pkg image whose digest is approved, while `--executable` names the installed browser binary used for version and runtime smoke checks. The command deletes any existing `dist`, builds the current source, runs the smoke, and removes `dist` again:

```bash
cd frontend
../_bmad/scripts/with_pab_toolchain.sh npm run preflight:brand -- \
  --brand edge \
  --channel current \
  --artifact "/path/to/approved-edge.pkg" \
  --executable "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
```

The command fails closed when the brand/channel is absent from the manifest, the current OS differs from `os.brandMatrixTarget`, the artifact digest differs, the optional executable digest differs, or the launched browser's full version differs. A PASS prints the exact approved record identifier and the actual artifact/executable SHA-256 values; it remains an optional preflight, not a formal brand-matrix report.

Ownership boundaries remain:

- `src/app` owns composition-level router, theme, and client configuration extension points;
- `src/domains/<domain>/index.ts` is the only public entry for another domain;
- `src/components` is reserved for reusable presentation components without business ownership;
- `src/shared` is reserved for frontend technical primitives, not authorization policy or business state;
- `docs/input/原型/frontend/**` remains read-only migration input and is not a production dependency.
- `package-lock.json` is the only approved lock and must remain tracked; `node_modules`, `dist`, `coverage`, `playwright-report` and `test-results` are generated and ignored.
- The approved school App/WebView baseline is `not-applicable`; this does not claim that mobile host acceptance passed.

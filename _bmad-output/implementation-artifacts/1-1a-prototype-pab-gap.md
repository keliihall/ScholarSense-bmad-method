# Story 1.1a：原型与 PAB-1.0.0 差异报告

- Generation ID：`sha256:08ab3ff93fa9423be45dfd980d70a8047414172314183a8f614599deca743181`
- 核验日期：2026-07-18
- 原型来源：`docs/input/原型/frontend`
- 对账 oracle：PAB-1.0.0 / AUTH-2026-07-17-001（只读批准基线）
- 后续 owner：表中各项按行唯一绑定 Story 1.1b、Story 1.1c；供应链运行证据由 Story 1.1d 产生。

## 本 Story 核验的当前事实

下表只陈述 `package.json` 的声明范围、`package-lock.json` 的解析版本和 PAB 静态 oracle。当前 `node_modules` 的存在不替代 lockfile，也不证明生产兼容性。

| 依赖 | 声明状态 | 原型声明范围 | lock 状态 | lockfile 解析版本 | PAB 精确基线 | 差异 | 分类 | 迁移风险 | owner | 处置 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| @element-plus/icons-vue | declared | ^2.3.1 | resolved | 2.3.2 | NOT-FROZEN | unfrozen-prototype-extra | unknown-blocked | PAB-1.0.0 未冻结该依赖，不能自动进入生产候选。 | 1.1c | 在 1.1c 中移除、替换或在 1.1c 中通过新 ADR 批准；默认不得进入生产候选。 |
| @types/node | declared | ^20.19.43 | resolved | 20.19.43 | NOT-FROZEN | unfrozen-prototype-extra | unknown-blocked | PAB-1.0.0 未冻结该依赖，不能自动进入生产候选。 | 1.1c | 在 1.1c 中移除、替换或在 1.1c 中通过新 ADR 批准；默认不得进入生产候选。 |
| @vitejs/plugin-vue | declared | ^5.0.4 | resolved | 5.2.4 | NOT-FROZEN | unfrozen-prototype-extra | unknown-blocked | PAB-1.0.0 未冻结该依赖，不能自动进入生产候选。 | 1.1c | 在 1.1c 中移除、替换或在 1.1c 中通过新 ADR 批准；默认不得进入生产候选。 |
| axe-core | not-declared | NOT-DECLARED | not-resolved | NOT-RESOLVED | 4.12.1 | missing-from-prototype | migrate | PAB 候选在原型中缺失，不能由当前原型行为证明兼容性。 | 1.1c | 由 1.1c 迁移并精确锁定为 4.12.1；本 Story 不升级依赖。 |
| axios | declared | ^1.6.8 | resolved | 1.18.1 | NOT-FROZEN | unfrozen-prototype-extra | unknown-blocked | PAB-1.0.0 未冻结该依赖，不能自动进入生产候选。 | 1.1c | 在 1.1c 中移除、替换或在 1.1c 中通过新 ADR 批准；默认不得进入生产候选。 |
| CloudEvents | not-applicable | NOT-APPLICABLE | not-applicable | NOT-APPLICABLE | 1.0.2 | not-applicable-to-frontend-prototype | reference-only | 该 PAB 基线不由当前纯前端原型声明或 lockfile 证明。 | 1.1b | 由 1.1b 保留 PAB-1.0.0 精确基线 1.0.2；本 Story 仅记录 oracle。 |
| ECharts | declared | ^5.5.1 | resolved | 5.6.0 | 6.1.0 | version-mismatch | reference-only | 原型解析版本与 PAB 精确基线不同，组合兼容性未知。 | 1.1c | 由 1.1c 迁移并精确锁定为 6.1.0；本 Story 不升级依赖。 |
| Element Plus | declared | ^2.7.8 | resolved | 2.14.2 | 2.14.3 | version-mismatch | migrate | 原型解析版本与 PAB 精确基线不同，组合兼容性未知。 | 1.1c | 由 1.1c 迁移并精确锁定为 2.14.3；本 Story 不升级依赖。 |
| JSON Schema | not-applicable | NOT-APPLICABLE | not-applicable | NOT-APPLICABLE | 2020-12 | not-applicable-to-frontend-prototype | reference-only | 该 PAB 基线不由当前纯前端原型声明或 lockfile 证明。 | 1.1b | 由 1.1b 保留 PAB-1.0.0 精确基线 2020-12；本 Story 仅记录 oracle。 |
| Node.js | not-declared | NOT-DECLARED | not-recorded | NOT-RECORDED | 24.18.0 LTS | runtime-unverified | unknown-blocked | 原型未提供可核验的运行时精确版本；本机版本不得替代受控证据。 | 1.1c | 由 1.1c 按 PAB-1.0.0 冻结 Node.js 24.18.0 LTS，并在 1.1d 产生运行证据。 |
| npm | not-declared | NOT-DECLARED | not-recorded | NOT-RECORDED | 11.16.0 | runtime-unverified | unknown-blocked | 原型未提供可核验的运行时精确版本；本机版本不得替代受控证据。 | 1.1c | 由 1.1c 按 PAB-1.0.0 冻结 npm 11.16.0，并在 1.1d 产生运行证据。 |
| OpenAPI | not-applicable | NOT-APPLICABLE | not-applicable | NOT-APPLICABLE | 3.1.2 | not-applicable-to-frontend-prototype | reference-only | 该 PAB 基线不由当前纯前端原型声明或 lockfile 证明。 | 1.1b | 由 1.1b 保留 PAB-1.0.0 精确基线 3.1.2；本 Story 仅记录 oracle。 |
| OpenJDK | not-applicable | NOT-APPLICABLE | not-applicable | NOT-APPLICABLE | 25 LTS | not-applicable-to-frontend-prototype | reference-only | 该 PAB 基线不由当前纯前端原型声明或 lockfile 证明。 | 1.1b | 由 1.1b 保留 PAB-1.0.0 精确基线 25 LTS；本 Story 仅记录 oracle。 |
| Pinia | declared | ^2.1.7 | resolved | 2.3.1 | 3.0.4 | version-mismatch | reference-only | 原型解析版本与 PAB 精确基线不同，组合兼容性未知。 | 1.1c | 由 1.1c 迁移并精确锁定为 3.0.4；本 Story 不升级依赖。 |
| Playwright | not-declared | NOT-DECLARED | not-resolved | NOT-RESOLVED | 1.61.1 | missing-from-prototype | migrate | PAB 候选在原型中缺失，不能由当前原型行为证明兼容性。 | 1.1c | 由 1.1c 迁移并精确锁定为 1.61.1；本 Story 不升级依赖。 |
| PostgreSQL | not-applicable | NOT-APPLICABLE | not-applicable | NOT-APPLICABLE | 18.4 | not-applicable-to-frontend-prototype | reference-only | 该 PAB 基线不由当前纯前端原型声明或 lockfile 证明。 | 1.1b | 由 1.1b 保留 PAB-1.0.0 精确基线 18.4；本 Story 仅记录 oracle。 |
| prettier | declared | ^3.2.5 | resolved | 3.8.4 | NOT-FROZEN | unfrozen-prototype-extra | unknown-blocked | PAB-1.0.0 未冻结该依赖，不能自动进入生产候选。 | 1.1c | 在 1.1c 中移除、替换或在 1.1c 中通过新 ADR 批准；默认不得进入生产候选。 |
| Spring Boot | not-applicable | NOT-APPLICABLE | not-applicable | NOT-APPLICABLE | 4.1.0 | not-applicable-to-frontend-prototype | reference-only | 该 PAB 基线不由当前纯前端原型声明或 lockfile 证明。 | 1.1b | 由 1.1b 保留 PAB-1.0.0 精确基线 4.1.0；本 Story 仅记录 oracle。 |
| TanStack Vue Query | not-declared | NOT-DECLARED | not-resolved | NOT-RESOLVED | 5.101.2 | missing-from-prototype | migrate | PAB 候选在原型中缺失，不能由当前原型行为证明兼容性。 | 1.1c | 由 1.1c 迁移并精确锁定为 5.101.2；本 Story 不升级依赖。 |
| TypeScript | declared | ^5.4.5 | resolved | 5.9.3 | @typescript/typescript6 6.0.2 | version-mismatch | reference-only | 原型解析版本与 PAB 精确基线不同，组合兼容性未知。 | 1.1c | 由 1.1c 迁移并精确锁定为 @typescript/typescript6 6.0.2；本 Story 不升级依赖。 |
| Vite | declared | ^5.2.10 | resolved | 5.4.21 | 8.1.5 | version-mismatch | reference-only | 原型解析版本与 PAB 精确基线不同，组合兼容性未知。 | 1.1c | 由 1.1c 迁移并精确锁定为 8.1.5；本 Story 不升级依赖。 |
| Vitest | not-declared | NOT-DECLARED | not-resolved | NOT-RESOLVED | 4.1.10 | missing-from-prototype | migrate | PAB 候选在原型中缺失，不能由当前原型行为证明兼容性。 | 1.1c | 由 1.1c 迁移并精确锁定为 4.1.10；本 Story 不升级依赖。 |
| Vue | declared | ^3.4.21 | resolved | 3.5.38 | 3.5.40 | version-mismatch | migrate | 原型解析版本与 PAB 精确基线不同，组合兼容性未知。 | 1.1c | 由 1.1c 迁移并精确锁定为 3.5.40；本 Story 不升级依赖。 |
| Vue Router | declared | ^4.3.3 | resolved | 4.6.4 | 4.6.4 | semver-range-not-exact | migrate | 即使版本接近或相同，仍缺生产 lock、兼容性与运行验收。 | 1.1c | 由 1.1c 迁移并精确锁定为 4.6.4；本 Story 不升级依赖。 |
| vue-echarts | declared | ^7.0.3 | resolved | 7.0.3 | 8.0.1 | version-mismatch | migrate | 原型解析版本与 PAB 精确基线不同，组合兼容性未知。 | 1.1c | 由 1.1c 迁移并精确锁定为 8.0.1；本 Story 不升级依赖。 |
| vue-tsc | declared | ^2.0.14 | resolved | 2.2.12 | 3.3.7 | version-mismatch | migrate | 原型解析版本与 PAB 精确基线不同，组合兼容性未知。 | 1.1c | 由 1.1c 迁移并精确锁定为 3.3.7；本 Story 不升级依赖。 |

## 1.1c / 1.1d 尚未产生的证据

本报告不声称以下证据已经产生或通过：精确生产 lock、组合兼容性、可复现构建、SBOM、provenance、签名、漏洞、许可证、制品提升、浏览器/WebView、性能、视觉与无障碍证据。

- Story 1.1c：批准生产前端组合、新 ADR（如确需未冻结依赖）、精确 lock、兼容性及浏览器/WebView profile。
- Story 1.1d：实际生成可复现构建、SBOM、provenance、签名、漏洞/许可证扫描，以及浏览器/WebView、视觉与无障碍实际报告；任一强制证据未通过时禁止制品提升。

## 机器可读差异

<!-- audit-generation-id: sha256:08ab3ff93fa9423be45dfd980d70a8047414172314183a8f614599deca743181 -->
<!-- audit-snapshot-metrics-receipt: sha256:4e1f90a13f0f8115b7b9e7315101bf42a10323be3d47724a91d34a44eef0dc71 -->
<!-- dependency-gap-json:start -->
```json
[
  {
    "classification": "unknown-blocked",
    "dependency": "@element-plus/icons-vue",
    "differenceType": "unfrozen-prototype-extra",
    "disposition": "在 1.1c 中移除、替换或在 1.1c 中通过新 ADR 批准；默认不得进入生产候选。",
    "lockfileState": "resolved",
    "lockfileVersion": "2.3.2",
    "migrationRisk": "PAB-1.0.0 未冻结该依赖，不能自动进入生产候选。",
    "ownerStory": "1.1c",
    "pabBaseline": "NOT-FROZEN",
    "packageName": "@element-plus/icons-vue",
    "prototypeRange": "^2.3.1",
    "prototypeState": "declared"
  },
  {
    "classification": "unknown-blocked",
    "dependency": "@types/node",
    "differenceType": "unfrozen-prototype-extra",
    "disposition": "在 1.1c 中移除、替换或在 1.1c 中通过新 ADR 批准；默认不得进入生产候选。",
    "lockfileState": "resolved",
    "lockfileVersion": "20.19.43",
    "migrationRisk": "PAB-1.0.0 未冻结该依赖，不能自动进入生产候选。",
    "ownerStory": "1.1c",
    "pabBaseline": "NOT-FROZEN",
    "packageName": "@types/node",
    "prototypeRange": "^20.19.43",
    "prototypeState": "declared"
  },
  {
    "classification": "unknown-blocked",
    "dependency": "@vitejs/plugin-vue",
    "differenceType": "unfrozen-prototype-extra",
    "disposition": "在 1.1c 中移除、替换或在 1.1c 中通过新 ADR 批准；默认不得进入生产候选。",
    "lockfileState": "resolved",
    "lockfileVersion": "5.2.4",
    "migrationRisk": "PAB-1.0.0 未冻结该依赖，不能自动进入生产候选。",
    "ownerStory": "1.1c",
    "pabBaseline": "NOT-FROZEN",
    "packageName": "@vitejs/plugin-vue",
    "prototypeRange": "^5.0.4",
    "prototypeState": "declared"
  },
  {
    "classification": "migrate",
    "dependency": "axe-core",
    "differenceType": "missing-from-prototype",
    "disposition": "由 1.1c 迁移并精确锁定为 4.12.1；本 Story 不升级依赖。",
    "lockfileState": "not-resolved",
    "lockfileVersion": "NOT-RESOLVED",
    "migrationRisk": "PAB 候选在原型中缺失，不能由当前原型行为证明兼容性。",
    "ownerStory": "1.1c",
    "pabBaseline": "4.12.1",
    "packageName": "axe-core",
    "prototypeRange": "NOT-DECLARED",
    "prototypeState": "not-declared"
  },
  {
    "classification": "unknown-blocked",
    "dependency": "axios",
    "differenceType": "unfrozen-prototype-extra",
    "disposition": "在 1.1c 中移除、替换或在 1.1c 中通过新 ADR 批准；默认不得进入生产候选。",
    "lockfileState": "resolved",
    "lockfileVersion": "1.18.1",
    "migrationRisk": "PAB-1.0.0 未冻结该依赖，不能自动进入生产候选。",
    "ownerStory": "1.1c",
    "pabBaseline": "NOT-FROZEN",
    "packageName": "axios",
    "prototypeRange": "^1.6.8",
    "prototypeState": "declared"
  },
  {
    "classification": "reference-only",
    "dependency": "CloudEvents",
    "differenceType": "not-applicable-to-frontend-prototype",
    "disposition": "由 1.1b 保留 PAB-1.0.0 精确基线 1.0.2；本 Story 仅记录 oracle。",
    "lockfileState": "not-applicable",
    "lockfileVersion": "NOT-APPLICABLE",
    "migrationRisk": "该 PAB 基线不由当前纯前端原型声明或 lockfile 证明。",
    "ownerStory": "1.1b",
    "pabBaseline": "1.0.2",
    "packageName": "RUNTIME",
    "prototypeRange": "NOT-APPLICABLE",
    "prototypeState": "not-applicable"
  },
  {
    "classification": "reference-only",
    "dependency": "ECharts",
    "differenceType": "version-mismatch",
    "disposition": "由 1.1c 迁移并精确锁定为 6.1.0；本 Story 不升级依赖。",
    "lockfileState": "resolved",
    "lockfileVersion": "5.6.0",
    "migrationRisk": "原型解析版本与 PAB 精确基线不同，组合兼容性未知。",
    "ownerStory": "1.1c",
    "pabBaseline": "6.1.0",
    "packageName": "echarts",
    "prototypeRange": "^5.5.1",
    "prototypeState": "declared"
  },
  {
    "classification": "migrate",
    "dependency": "Element Plus",
    "differenceType": "version-mismatch",
    "disposition": "由 1.1c 迁移并精确锁定为 2.14.3；本 Story 不升级依赖。",
    "lockfileState": "resolved",
    "lockfileVersion": "2.14.2",
    "migrationRisk": "原型解析版本与 PAB 精确基线不同，组合兼容性未知。",
    "ownerStory": "1.1c",
    "pabBaseline": "2.14.3",
    "packageName": "element-plus",
    "prototypeRange": "^2.7.8",
    "prototypeState": "declared"
  },
  {
    "classification": "reference-only",
    "dependency": "JSON Schema",
    "differenceType": "not-applicable-to-frontend-prototype",
    "disposition": "由 1.1b 保留 PAB-1.0.0 精确基线 2020-12；本 Story 仅记录 oracle。",
    "lockfileState": "not-applicable",
    "lockfileVersion": "NOT-APPLICABLE",
    "migrationRisk": "该 PAB 基线不由当前纯前端原型声明或 lockfile 证明。",
    "ownerStory": "1.1b",
    "pabBaseline": "2020-12",
    "packageName": "RUNTIME",
    "prototypeRange": "NOT-APPLICABLE",
    "prototypeState": "not-applicable"
  },
  {
    "classification": "unknown-blocked",
    "dependency": "Node.js",
    "differenceType": "runtime-unverified",
    "disposition": "由 1.1c 按 PAB-1.0.0 冻结 Node.js 24.18.0 LTS，并在 1.1d 产生运行证据。",
    "lockfileState": "not-recorded",
    "lockfileVersion": "NOT-RECORDED",
    "migrationRisk": "原型未提供可核验的运行时精确版本；本机版本不得替代受控证据。",
    "ownerStory": "1.1c",
    "pabBaseline": "24.18.0 LTS",
    "packageName": "RUNTIME",
    "prototypeRange": "NOT-DECLARED",
    "prototypeState": "not-declared"
  },
  {
    "classification": "unknown-blocked",
    "dependency": "npm",
    "differenceType": "runtime-unverified",
    "disposition": "由 1.1c 按 PAB-1.0.0 冻结 npm 11.16.0，并在 1.1d 产生运行证据。",
    "lockfileState": "not-recorded",
    "lockfileVersion": "NOT-RECORDED",
    "migrationRisk": "原型未提供可核验的运行时精确版本；本机版本不得替代受控证据。",
    "ownerStory": "1.1c",
    "pabBaseline": "11.16.0",
    "packageName": "RUNTIME",
    "prototypeRange": "NOT-DECLARED",
    "prototypeState": "not-declared"
  },
  {
    "classification": "reference-only",
    "dependency": "OpenAPI",
    "differenceType": "not-applicable-to-frontend-prototype",
    "disposition": "由 1.1b 保留 PAB-1.0.0 精确基线 3.1.2；本 Story 仅记录 oracle。",
    "lockfileState": "not-applicable",
    "lockfileVersion": "NOT-APPLICABLE",
    "migrationRisk": "该 PAB 基线不由当前纯前端原型声明或 lockfile 证明。",
    "ownerStory": "1.1b",
    "pabBaseline": "3.1.2",
    "packageName": "RUNTIME",
    "prototypeRange": "NOT-APPLICABLE",
    "prototypeState": "not-applicable"
  },
  {
    "classification": "reference-only",
    "dependency": "OpenJDK",
    "differenceType": "not-applicable-to-frontend-prototype",
    "disposition": "由 1.1b 保留 PAB-1.0.0 精确基线 25 LTS；本 Story 仅记录 oracle。",
    "lockfileState": "not-applicable",
    "lockfileVersion": "NOT-APPLICABLE",
    "migrationRisk": "该 PAB 基线不由当前纯前端原型声明或 lockfile 证明。",
    "ownerStory": "1.1b",
    "pabBaseline": "25 LTS",
    "packageName": "RUNTIME",
    "prototypeRange": "NOT-APPLICABLE",
    "prototypeState": "not-applicable"
  },
  {
    "classification": "reference-only",
    "dependency": "Pinia",
    "differenceType": "version-mismatch",
    "disposition": "由 1.1c 迁移并精确锁定为 3.0.4；本 Story 不升级依赖。",
    "lockfileState": "resolved",
    "lockfileVersion": "2.3.1",
    "migrationRisk": "原型解析版本与 PAB 精确基线不同，组合兼容性未知。",
    "ownerStory": "1.1c",
    "pabBaseline": "3.0.4",
    "packageName": "pinia",
    "prototypeRange": "^2.1.7",
    "prototypeState": "declared"
  },
  {
    "classification": "migrate",
    "dependency": "Playwright",
    "differenceType": "missing-from-prototype",
    "disposition": "由 1.1c 迁移并精确锁定为 1.61.1；本 Story 不升级依赖。",
    "lockfileState": "not-resolved",
    "lockfileVersion": "NOT-RESOLVED",
    "migrationRisk": "PAB 候选在原型中缺失，不能由当前原型行为证明兼容性。",
    "ownerStory": "1.1c",
    "pabBaseline": "1.61.1",
    "packageName": "@playwright/test",
    "prototypeRange": "NOT-DECLARED",
    "prototypeState": "not-declared"
  },
  {
    "classification": "reference-only",
    "dependency": "PostgreSQL",
    "differenceType": "not-applicable-to-frontend-prototype",
    "disposition": "由 1.1b 保留 PAB-1.0.0 精确基线 18.4；本 Story 仅记录 oracle。",
    "lockfileState": "not-applicable",
    "lockfileVersion": "NOT-APPLICABLE",
    "migrationRisk": "该 PAB 基线不由当前纯前端原型声明或 lockfile 证明。",
    "ownerStory": "1.1b",
    "pabBaseline": "18.4",
    "packageName": "RUNTIME",
    "prototypeRange": "NOT-APPLICABLE",
    "prototypeState": "not-applicable"
  },
  {
    "classification": "unknown-blocked",
    "dependency": "prettier",
    "differenceType": "unfrozen-prototype-extra",
    "disposition": "在 1.1c 中移除、替换或在 1.1c 中通过新 ADR 批准；默认不得进入生产候选。",
    "lockfileState": "resolved",
    "lockfileVersion": "3.8.4",
    "migrationRisk": "PAB-1.0.0 未冻结该依赖，不能自动进入生产候选。",
    "ownerStory": "1.1c",
    "pabBaseline": "NOT-FROZEN",
    "packageName": "prettier",
    "prototypeRange": "^3.2.5",
    "prototypeState": "declared"
  },
  {
    "classification": "reference-only",
    "dependency": "Spring Boot",
    "differenceType": "not-applicable-to-frontend-prototype",
    "disposition": "由 1.1b 保留 PAB-1.0.0 精确基线 4.1.0；本 Story 仅记录 oracle。",
    "lockfileState": "not-applicable",
    "lockfileVersion": "NOT-APPLICABLE",
    "migrationRisk": "该 PAB 基线不由当前纯前端原型声明或 lockfile 证明。",
    "ownerStory": "1.1b",
    "pabBaseline": "4.1.0",
    "packageName": "RUNTIME",
    "prototypeRange": "NOT-APPLICABLE",
    "prototypeState": "not-applicable"
  },
  {
    "classification": "migrate",
    "dependency": "TanStack Vue Query",
    "differenceType": "missing-from-prototype",
    "disposition": "由 1.1c 迁移并精确锁定为 5.101.2；本 Story 不升级依赖。",
    "lockfileState": "not-resolved",
    "lockfileVersion": "NOT-RESOLVED",
    "migrationRisk": "PAB 候选在原型中缺失，不能由当前原型行为证明兼容性。",
    "ownerStory": "1.1c",
    "pabBaseline": "5.101.2",
    "packageName": "@tanstack/vue-query",
    "prototypeRange": "NOT-DECLARED",
    "prototypeState": "not-declared"
  },
  {
    "classification": "reference-only",
    "dependency": "TypeScript",
    "differenceType": "version-mismatch",
    "disposition": "由 1.1c 迁移并精确锁定为 @typescript/typescript6 6.0.2；本 Story 不升级依赖。",
    "lockfileState": "resolved",
    "lockfileVersion": "5.9.3",
    "migrationRisk": "原型解析版本与 PAB 精确基线不同，组合兼容性未知。",
    "ownerStory": "1.1c",
    "pabBaseline": "@typescript/typescript6 6.0.2",
    "packageName": "typescript",
    "prototypeRange": "^5.4.5",
    "prototypeState": "declared"
  },
  {
    "classification": "reference-only",
    "dependency": "Vite",
    "differenceType": "version-mismatch",
    "disposition": "由 1.1c 迁移并精确锁定为 8.1.5；本 Story 不升级依赖。",
    "lockfileState": "resolved",
    "lockfileVersion": "5.4.21",
    "migrationRisk": "原型解析版本与 PAB 精确基线不同，组合兼容性未知。",
    "ownerStory": "1.1c",
    "pabBaseline": "8.1.5",
    "packageName": "vite",
    "prototypeRange": "^5.2.10",
    "prototypeState": "declared"
  },
  {
    "classification": "migrate",
    "dependency": "Vitest",
    "differenceType": "missing-from-prototype",
    "disposition": "由 1.1c 迁移并精确锁定为 4.1.10；本 Story 不升级依赖。",
    "lockfileState": "not-resolved",
    "lockfileVersion": "NOT-RESOLVED",
    "migrationRisk": "PAB 候选在原型中缺失，不能由当前原型行为证明兼容性。",
    "ownerStory": "1.1c",
    "pabBaseline": "4.1.10",
    "packageName": "vitest",
    "prototypeRange": "NOT-DECLARED",
    "prototypeState": "not-declared"
  },
  {
    "classification": "migrate",
    "dependency": "Vue",
    "differenceType": "version-mismatch",
    "disposition": "由 1.1c 迁移并精确锁定为 3.5.40；本 Story 不升级依赖。",
    "lockfileState": "resolved",
    "lockfileVersion": "3.5.38",
    "migrationRisk": "原型解析版本与 PAB 精确基线不同，组合兼容性未知。",
    "ownerStory": "1.1c",
    "pabBaseline": "3.5.40",
    "packageName": "vue",
    "prototypeRange": "^3.4.21",
    "prototypeState": "declared"
  },
  {
    "classification": "migrate",
    "dependency": "Vue Router",
    "differenceType": "semver-range-not-exact",
    "disposition": "由 1.1c 迁移并精确锁定为 4.6.4；本 Story 不升级依赖。",
    "lockfileState": "resolved",
    "lockfileVersion": "4.6.4",
    "migrationRisk": "即使版本接近或相同，仍缺生产 lock、兼容性与运行验收。",
    "ownerStory": "1.1c",
    "pabBaseline": "4.6.4",
    "packageName": "vue-router",
    "prototypeRange": "^4.3.3",
    "prototypeState": "declared"
  },
  {
    "classification": "migrate",
    "dependency": "vue-echarts",
    "differenceType": "version-mismatch",
    "disposition": "由 1.1c 迁移并精确锁定为 8.0.1；本 Story 不升级依赖。",
    "lockfileState": "resolved",
    "lockfileVersion": "7.0.3",
    "migrationRisk": "原型解析版本与 PAB 精确基线不同，组合兼容性未知。",
    "ownerStory": "1.1c",
    "pabBaseline": "8.0.1",
    "packageName": "vue-echarts",
    "prototypeRange": "^7.0.3",
    "prototypeState": "declared"
  },
  {
    "classification": "migrate",
    "dependency": "vue-tsc",
    "differenceType": "version-mismatch",
    "disposition": "由 1.1c 迁移并精确锁定为 3.3.7；本 Story 不升级依赖。",
    "lockfileState": "resolved",
    "lockfileVersion": "2.2.12",
    "migrationRisk": "原型解析版本与 PAB 精确基线不同，组合兼容性未知。",
    "ownerStory": "1.1c",
    "pabBaseline": "3.3.7",
    "packageName": "vue-tsc",
    "prototypeRange": "^2.0.14",
    "prototypeState": "declared"
  }
]
```
<!-- dependency-gap-json:end -->

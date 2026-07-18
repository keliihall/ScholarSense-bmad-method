# Story 1.1a：生产资产清单

- 清单 Schema：3.2.0
- 审计脚本版本：2.9.0
- Generation ID：`sha256:08ab3ff93fa9423be45dfd980d70a8047414172314183a8f614599deca743181`
- 生成时间：2026-07-18T20:48:16+08:00
- 核验日期：2026-07-18
- 受控输入摘要：`sha256:bd0c160dd756150af7bee55c2616170ebed0ea6752af5fe15dcb66989d1980a5`

## 当前可见事实与边界

- Git 仓库元数据：未发现。远程 URL、分支和 commit 不可据此推断。
- 生产后端/数据库迁移：未发现。Architecture 中的 `backend/` 是 1.1b 目标 seed。
- 生产前端工程：未发现。任何现存 `frontend/` 仍须由 1.1b 核验 owner、来源与真实性。
- 公共契约定义：未发现。任何现存 `contracts/` 不自动等同于已批准或已兼容的运行契约。
- CI 定义：未发现。本 Story 不声称构建、扫描或签名通过。
- 部署/基础设施工程：未发现。不得虚构部署平台或制品库。
- `docs/input/原型/frontend` 是 Vite 5 / Vue 3 原型；该事实从同 generation 的 package manifest 与 lockfile 动态提取，PAB 差异详见配套报告。源码与页面意图仅作迁移输入，`dist` 不是可提升制品，`node_modules` 不是依赖分发源。
- 原型源码受控文件数：54。机器计数按审计契约忽略 `.DS_Store`；原始目录枚举若包含此类元数据，数字可能更大。
- 原型注释中的“微前端”“网关”“各微服务”属于未受控原型假设，不覆盖 AD-1 模块化单体和 AD-28。

## 资产明细

| assetId | 类型 | 路径/来源 | owner | 版本/摘要 | 日期 | 分类 | 建议 | owner Story | 风险 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| planning.architecture-spine | controlled-planning-baseline | _bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md | AUTH-2026-07-17-001 受控架构基线 | sha256:730d69a7a6eb78e7317873376b3e078770ee41afa37b235c80fbf0fc81270a10 | 2026-07-18 | reuse-as-is | reference-only | 1.1b | 不得把目标源码树误报为现有生产资产。 |
| planning.pab-1.0.0 | approved-version-oracle | _bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md | 项目总负责人与委托决策人（Hei） | sha256:66c8d6914fd94b8f101a20757fb81fe355894982992531f81cfa0ccc38aadcb0 | 2026-07-18 | reuse-as-is | reference-only | 1.1c | 精确 lock、兼容性与供应链证据仍由 1.1c/1.1d 产生。 |
| prototype.components | ui-components | docs/input/原型/frontend/src/components | UNKNOWN | sha256:7acda3b18cfa6e51e99dee98e5b21f996ce3011907b527a0f2f9e8c083e3ebf2 | 2026-07-18 | unknown-blocked | migrate | 1.1b | 原型组件与 PAB 依赖组合未验证。 |
| prototype.config | prototype-configuration | docs/input/原型/frontend/.env.example; docs/input/原型/frontend/.prettierrc.json; docs/input/原型/frontend/index.html; docs/input/原型/frontend/tsconfig.app.json; docs/input/原型/frontend/tsconfig.json; docs/input/原型/frontend/tsconfig.node.json; docs/input/原型/frontend/vite.config.ts | UNKNOWN | sha256:17ee67ca4f9d5c0a8d46c6f376b8c69038826b41b1745cec2ce50f00527e4329 | 2026-07-18 | unknown-blocked | reference-only | 1.1c | 原型假设不得覆盖 AD-1 模块化单体与 AD-28 生产基线。 |
| prototype.dist | generated-build-output | docs/input/原型/frontend/dist | UNKNOWN | sha256:c55cf119f6d6487eb94f909bfb83957007509a47106d8f48af9feb6d7f260489 | 2026-07-18 | unknown-blocked | replace | 1.1d | 无可复现构建、digest、签名或扫描证据。 |
| prototype.layouts | layout | docs/input/原型/frontend/src/layouts | UNKNOWN | sha256:3d3815f55ab9deddba118f7aa0baf049bc3a2b86218a8de5b1f6864b87077c15 | 2026-07-18 | unknown-blocked | migrate | 1.1b | 未经过生产宿主、WebView 与无障碍验收。 |
| prototype.lockfile | dependency-lock-snapshot | docs/input/原型/frontend/package-lock.json | UNKNOWN | sha256:455c859fa387a2289af913c68dc6832f462ebdb1c6dd42a1996a3d94cd6b9640 | 2026-07-18 | unknown-blocked | reference-only | 1.1c | 不得作为未来生产 lockfile 提升。 |
| prototype.mocks | mock-behavior | docs/input/原型/frontend/src/mock | UNKNOWN | sha256:1fb05ee27a8e0eec02ac7f33ffe07a65e0f49f5ed1f9245c1e3c5ef2354fbad0 | 2026-07-18 | unknown-blocked | replace | 1.1b | 把 mock 当真实性会绕过授权、失败与审计语义。 |
| prototype.node-modules | installed-dependency-tree | docs/input/原型/frontend/node_modules | UNKNOWN | sha256:60007ddf2ac200177bceb764f13644f21640f63132178940e5f6a54940ded532 | 2026-07-18 | unknown-blocked | replace | 1.1d | 安装树可能含缓存、平台差异与未受控传递依赖。 |
| prototype.package-manifest | dependency-manifest | docs/input/原型/frontend/package.json | UNKNOWN | sha256:144bbbdc0be6566efd04aa2f87a044a6f98dc3b270a61f4442b51d4c126861e9 | 2026-07-18 | unknown-blocked | reference-only | 1.1c | 范围声明不是 PAB 精确 lock。 |
| prototype.routes | routing | docs/input/原型/frontend/src/router | UNKNOWN | sha256:29f42137e81dfefcb4f12789636538be4bbad11b5d15742b62ad1a1a5ed5b4b7 | 2026-07-18 | unknown-blocked | migrate | 1.1b | 不得继承原型认证与授权假设。 |
| prototype.source | source-code | docs/input/原型/frontend/src | UNKNOWN | sha256:db521ec80effaa68ede1e4b50316f8536c14f728a74f420ae9dbc45a14d2ad08 | 2026-07-18 | unknown-blocked | migrate | 1.1b | mock/auth/store/API 行为不是生产真实性。 |
| prototype.static-assets | static-assets | docs/input/原型/frontend/public | UNKNOWN | sha256:e66cc5e2b62a133404d570c899f65b466ede881c0fe74aa257e29d24111519fc | 2026-07-18 | unknown-blocked | reference-only | 1.1b | 来源、许可证和生产优化状态未核验。 |
| prototype.stores | client-state | docs/input/原型/frontend/src/stores | UNKNOWN | sha256:68fda0e820c1c9b99979bc746db176e51a0a1adc789bdc4b65b26a839c0485e1 | 2026-07-18 | unknown-blocked | migrate | 1.1b | Pinia 2 和持久化行为不得成为生产基线。 |
| prototype.styles | styles | docs/input/原型/frontend/src/styles | UNKNOWN | sha256:0b44c62b616f13084de52e5ed6f28fe41f73530bc1405efa235c4f70bcb5c4a7 | 2026-07-18 | unknown-blocked | migrate | 1.1b | 尚无视觉回归、WCAG 2.2 AA 或宿主适配证据。 |
| prototype.types | typescript-types | docs/input/原型/frontend/src/types | UNKNOWN | sha256:42b15322a3a2f2c15c9185196378256b07ca7265674bcc8fdf3ad8b63856e0e4 | 2026-07-18 | unknown-blocked | migrate | 1.1b | 原型类型不能替代受控 OpenAPI/事件 schema。 |
| workspace.ci-definitions | ci-metadata | .github/workflows、GitLab/Jenkins/Azure/CircleCI/Travis/Bitbucket/Buildkite/Drone（未发现） | UNKNOWN | UNAVAILABLE | 2026-07-18 | unknown-blocked | replace | 1.1d | 不得推断已执行构建、扫描、SBOM、provenance 或签名。 |
| workspace.contract-definitions | api-and-event-contracts | contracts/ 或根级 OpenAPI、AsyncAPI、JSON Schema（未发现） | UNKNOWN | UNAVAILABLE | 2026-07-18 | unknown-blocked | reference-only | 1.1b | 现存文件不自动等同于已批准的 PAB/PIC 契约或运行兼容性证据。 |
| workspace.deployment-engineering | deployment-metadata | deploy/、容器/Compose、Terraform、Helm、Kubernetes 或一级项目部署指纹（未发现） | UNKNOWN | UNAVAILABLE | 2026-07-18 | unknown-blocked | replace | 1.1d | 不得虚构部署平台、构建镜像 digest 或制品库。 |
| workspace.git-repository | repository-metadata | .git（未发现） | UNKNOWN | UNAVAILABLE | 2026-07-18 | unknown-blocked | reference-only | 1.1b | 缺失版本历史与远程来源证据，禁止声称仓库核验通过。 |
| workspace.production-backend | production-backend | backend/、根级或一级项目中的 Maven/Gradle 指纹（未发现） | UNKNOWN | UNAVAILABLE | 2026-07-18 | unknown-blocked | replace | 1.1b | Architecture 的 backend/ 是目标 seed，不是可复用现状。 |
| workspace.production-frontend | production-frontend | frontend/ 或一级前端项目指纹（未发现） | UNKNOWN | UNAVAILABLE | 2026-07-18 | unknown-blocked | migrate | 1.1b | owner、来源与生产真实性尚未闭合，需由 1.1b 建立受控工程边界。 |

## unknown-blocked 风险清单

- `prototype.components` → 1.1b：当前 owner 或来源/版本证据未闭合。 影响：原型组件与 PAB 依赖组合未验证。
- `prototype.config` → 1.1c：当前 owner 或来源/版本证据未闭合。 影响：原型假设不得覆盖 AD-1 模块化单体与 AD-28 生产基线。
- `prototype.dist` → 1.1d：当前 owner 或来源/版本证据未闭合。 影响：无可复现构建、digest、签名或扫描证据。
- `prototype.layouts` → 1.1b：当前 owner 或来源/版本证据未闭合。 影响：未经过生产宿主、WebView 与无障碍验收。
- `prototype.lockfile` → 1.1c：当前 owner 或来源/版本证据未闭合。 影响：不得作为未来生产 lockfile 提升。
- `prototype.mocks` → 1.1b：当前 owner 或来源/版本证据未闭合。 影响：把 mock 当真实性会绕过授权、失败与审计语义。
- `prototype.node-modules` → 1.1d：当前 owner 或来源/版本证据未闭合。 影响：安装树可能含缓存、平台差异与未受控传递依赖。
- `prototype.package-manifest` → 1.1c：当前 owner 或来源/版本证据未闭合。 影响：范围声明不是 PAB 精确 lock。
- `prototype.routes` → 1.1b：当前 owner 或来源/版本证据未闭合。 影响：不得继承原型认证与授权假设。
- `prototype.source` → 1.1b：当前 owner 或来源/版本证据未闭合。 影响：mock/auth/store/API 行为不是生产真实性。
- `prototype.static-assets` → 1.1b：当前 owner 或来源/版本证据未闭合。 影响：来源、许可证和生产优化状态未核验。
- `prototype.stores` → 1.1b：当前 owner 或来源/版本证据未闭合。 影响：Pinia 2 和持久化行为不得成为生产基线。
- `prototype.styles` → 1.1b：当前 owner 或来源/版本证据未闭合。 影响：尚无视觉回归、WCAG 2.2 AA 或宿主适配证据。
- `prototype.types` → 1.1b：当前 owner 或来源/版本证据未闭合。 影响：原型类型不能替代受控 OpenAPI/事件 schema。
- `workspace.ci-definitions` → 1.1d：当前 owner 或来源/版本证据未闭合。 影响：不得推断已执行构建、扫描、SBOM、provenance 或签名。
- `workspace.contract-definitions` → 1.1b：当前 owner 或来源/版本证据未闭合。 影响：现存文件不自动等同于已批准的 PAB/PIC 契约或运行兼容性证据。
- `workspace.deployment-engineering` → 1.1d：当前 owner 或来源/版本证据未闭合。 影响：不得虚构部署平台、构建镜像 digest 或制品库。
- `workspace.git-repository` → 1.1b：当前 owner 或来源/版本证据未闭合。 影响：缺失版本历史与远程来源证据，禁止声称仓库核验通过。
- `workspace.production-backend` → 1.1b：当前 owner 或来源/版本证据未闭合。 影响：Architecture 的 backend/ 是目标 seed，不是可复用现状。
- `workspace.production-frontend` → 1.1b：当前 owner 或来源/版本证据未闭合。 影响：owner、来源与生产真实性尚未闭合，需由 1.1b 建立受控工程边界。

## 执行证据

- 生成：`python3 /Users/hei/project/ScholarSense-bmad-method/_bmad/scripts/audit_production_assets.py generate --workspace-root /Users/hei/project/ScholarSense-bmad-method --prototype-root 'docs/input/原型/frontend' --architecture-source _bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md --pab-source _bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md --inventory-output _bmad-output/implementation-artifacts/1-1a-production-asset-inventory.md --schema-output _bmad-output/implementation-artifacts/1-1a-production-asset-inventory.schema.json --gap-output _bmad-output/implementation-artifacts/1-1a-prototype-pab-gap.md --generated-at 2026-07-18T20:48:16+08:00 --force`
- 重放命令（命令本身不代表已执行）：`python3 /Users/hei/project/ScholarSense-bmad-method/_bmad/scripts/audit_production_assets.py check --workspace-root /Users/hei/project/ScholarSense-bmad-method --prototype-root 'docs/input/原型/frontend' --architecture-source _bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md --pab-source _bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md --inventory-output _bmad-output/implementation-artifacts/1-1a-production-asset-inventory.md --schema-output _bmad-output/implementation-artifacts/1-1a-production-asset-inventory.schema.json --gap-output _bmad-output/implementation-artifacts/1-1a-prototype-pab-gap.md`
- 脚本：2.9.0 / `sha256:a8d0a48fceb74644d24fe142aea055890d21ec6b2267096b71bbefcab2fab219`
- 输入范围：`.buildkite`, `.circleci`, `.drone.yml`, `.git`, `.github/workflows`, `.gitlab-ci.yml`, `.nvmrc`, `.travis.yaml`, `.travis.yml`, `Containerfile`, `Dockerfile`, `Jenkinsfile`, `_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md`, `_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md`, `appveyor.yaml`, `appveyor.yml`, `asyncapi.json`, `asyncapi.yaml`, `asyncapi.yml`, `azure-pipelines.yml`, `backend`, `bitbucket-pipelines.yml`, `build.gradle`, `build.gradle.kts`, `compose.yaml`, `compose.yml`, `contracts`, `database/migrations`, `db/migrations`, `deploy`, `docker-compose.yaml`, `docker-compose.yml`, `docs/input/原型/frontend/.env.example`, `docs/input/原型/frontend/.eslintrc`, `docs/input/原型/frontend/.eslintrc.cjs`, `docs/input/原型/frontend/.eslintrc.js`, `docs/input/原型/frontend/.eslintrc.json`, `docs/input/原型/frontend/.nvmrc`, `docs/input/原型/frontend/.prettierrc`, `docs/input/原型/frontend/.prettierrc.js`, `docs/input/原型/frontend/.prettierrc.json`, `docs/input/原型/frontend/cypress.config.js`, `docs/input/原型/frontend/cypress.config.mjs`, `docs/input/原型/frontend/cypress.config.ts`, `docs/input/原型/frontend/dist`, `docs/input/原型/frontend/eslint.config.js`, `docs/input/原型/frontend/eslint.config.mjs`, `docs/input/原型/frontend/eslint.config.ts`, `docs/input/原型/frontend/index.html`, `docs/input/原型/frontend/jest.config.js`, `docs/input/原型/frontend/jest.config.ts`, `docs/input/原型/frontend/node_modules`, `docs/input/原型/frontend/package-lock.json`, `docs/input/原型/frontend/package.json`, `docs/input/原型/frontend/playwright.config.js`, `docs/input/原型/frontend/playwright.config.mjs`, `docs/input/原型/frontend/playwright.config.ts`, `docs/input/原型/frontend/postcss.config.cjs`, `docs/input/原型/frontend/postcss.config.js`, `docs/input/原型/frontend/postcss.config.mjs`, `docs/input/原型/frontend/postcss.config.ts`, `docs/input/原型/frontend/prettier.config.js`, `docs/input/原型/frontend/prettier.config.mjs`, `docs/input/原型/frontend/public`, `docs/input/原型/frontend/src`, `docs/input/原型/frontend/tailwind.config.cjs`, `docs/input/原型/frontend/tailwind.config.js`, `docs/input/原型/frontend/tailwind.config.mjs`, `docs/input/原型/frontend/tailwind.config.ts`, `docs/input/原型/frontend/tsconfig.app.json`, `docs/input/原型/frontend/tsconfig.json`, `docs/input/原型/frontend/tsconfig.node.json`, `docs/input/原型/frontend/vite.config.js`, `docs/input/原型/frontend/vite.config.mjs`, `docs/input/原型/frontend/vite.config.mts`, `docs/input/原型/frontend/vite.config.ts`, `docs/input/原型/frontend/vitest.config.js`, `docs/input/原型/frontend/vitest.config.mjs`, `docs/input/原型/frontend/vitest.config.ts`, `docs/input/原型/frontend/wdio.conf.js`, `docs/input/原型/frontend/wdio.conf.ts`, `frontend`, `gradlew`, `helm`, `infra`, `k8s`, `kubernetes`, `manifests`, `migrations`, `mvnw`, `openapi.json`, `openapi.yaml`, `openapi.yml`, `pom.xml`, `schema.json`, `settings.gradle`, `settings.gradle.kts`, `swagger.json`, `swagger.yaml`, `swagger.yml`, `terragrunt.hcl`
- 结果范围：generation-and-in-memory-validation；结果：passed；未解决项：20
- 测试与漂移检查验收回执：Story Dev Agent Record（不属于机器清单自证范围）。

| 步骤 | 命令 | 状态 | 退出码 |
| --- | --- | --- | --- |
| generate | `python3 /Users/hei/project/ScholarSense-bmad-method/_bmad/scripts/audit_production_assets.py generate --workspace-root /Users/hei/project/ScholarSense-bmad-method --prototype-root 'docs/input/原型/frontend' --architecture-source _bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md --pab-source _bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md --inventory-output _bmad-output/implementation-artifacts/1-1a-production-asset-inventory.md --schema-output _bmad-output/implementation-artifacts/1-1a-production-asset-inventory.schema.json --gap-output _bmad-output/implementation-artifacts/1-1a-prototype-pab-gap.md --generated-at 2026-07-18T20:48:16+08:00 --force` | passed | 0 |
| schema-validation | `in-memory published JSON Schema validation` | passed | 0 |
| gap-validation | `in-memory dependency gap contract validation` | passed | 0 |

## 机器可读清单

<!-- asset-inventory-json:start -->
```json
{
  "assets": [
    {
      "assetId": "planning.architecture-spine",
      "checkedPaths": [
        "_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md"
      ],
      "classification": "reuse-as-is",
      "dispositionReason": "作为 AD-1/AD-28 的只读受控约束使用。",
      "evidenceLinks": [
        "_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md"
      ],
      "metrics": {
        "regularFileByteCount": 52528,
        "regularFileCount": 1,
        "scope": "recursive-regular-files"
      },
      "owner": "AUTH-2026-07-17-001 受控架构基线",
      "ownerStory": "1.1b",
      "pathOrSource": "_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "reference-only",
      "risk": "不得把目标源码树误报为现有生产资产。",
      "snapshotOnly": false,
      "type": "controlled-planning-baseline",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:730d69a7a6eb78e7317873376b3e078770ee41afa37b235c80fbf0fc81270a10"
    },
    {
      "assetId": "planning.pab-1.0.0",
      "checkedPaths": [
        "_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md"
      ],
      "classification": "reuse-as-is",
      "dispositionReason": "仅作为 PAB-1.0.0 对账 oracle，不代表运行证据通过。",
      "evidenceLinks": [
        "_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md"
      ],
      "metrics": {
        "regularFileByteCount": 37355,
        "regularFileCount": 1,
        "scope": "recursive-regular-files"
      },
      "owner": "项目总负责人与委托决策人（Hei）",
      "ownerStory": "1.1c",
      "pathOrSource": "_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "reference-only",
      "risk": "精确 lock、兼容性与供应链证据仍由 1.1c/1.1d 产生。",
      "snapshotOnly": false,
      "type": "approved-version-oracle",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:66c8d6914fd94b8f101a20757fb81fe355894982992531f81cfa0ccc38aadcb0"
    },
    {
      "assetId": "prototype.components",
      "checkedPaths": [
        "docs/input/原型/frontend/src/components"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "组件意图可迁移。",
      "evidenceLinks": [
        "docs/input/原型/frontend/src/components"
      ],
      "metrics": {
        "regularFileByteCount": 18664,
        "regularFileCount": 13,
        "scope": "recursive-regular-files"
      },
      "owner": "UNKNOWN",
      "ownerStory": "1.1b",
      "pathOrSource": "docs/input/原型/frontend/src/components",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "migrate",
      "risk": "原型组件与 PAB 依赖组合未验证。",
      "snapshotOnly": false,
      "type": "ui-components",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:7acda3b18cfa6e51e99dee98e5b21f996ce3011907b527a0f2f9e8c083e3ebf2"
    },
    {
      "assetId": "prototype.config",
      "checkedPaths": [
        "docs/input/原型/frontend/.env.example",
        "docs/input/原型/frontend/.eslintrc",
        "docs/input/原型/frontend/.eslintrc.cjs",
        "docs/input/原型/frontend/.eslintrc.js",
        "docs/input/原型/frontend/.eslintrc.json",
        "docs/input/原型/frontend/.nvmrc",
        "docs/input/原型/frontend/.prettierrc",
        "docs/input/原型/frontend/.prettierrc.js",
        "docs/input/原型/frontend/.prettierrc.json",
        "docs/input/原型/frontend/cypress.config.js",
        "docs/input/原型/frontend/cypress.config.mjs",
        "docs/input/原型/frontend/cypress.config.ts",
        "docs/input/原型/frontend/eslint.config.js",
        "docs/input/原型/frontend/eslint.config.mjs",
        "docs/input/原型/frontend/eslint.config.ts",
        "docs/input/原型/frontend/index.html",
        "docs/input/原型/frontend/jest.config.js",
        "docs/input/原型/frontend/jest.config.ts",
        "docs/input/原型/frontend/playwright.config.js",
        "docs/input/原型/frontend/playwright.config.mjs",
        "docs/input/原型/frontend/playwright.config.ts",
        "docs/input/原型/frontend/postcss.config.cjs",
        "docs/input/原型/frontend/postcss.config.js",
        "docs/input/原型/frontend/postcss.config.mjs",
        "docs/input/原型/frontend/postcss.config.ts",
        "docs/input/原型/frontend/prettier.config.js",
        "docs/input/原型/frontend/prettier.config.mjs",
        "docs/input/原型/frontend/tailwind.config.cjs",
        "docs/input/原型/frontend/tailwind.config.js",
        "docs/input/原型/frontend/tailwind.config.mjs",
        "docs/input/原型/frontend/tailwind.config.ts",
        "docs/input/原型/frontend/tsconfig.app.json",
        "docs/input/原型/frontend/tsconfig.json",
        "docs/input/原型/frontend/tsconfig.node.json",
        "docs/input/原型/frontend/vite.config.js",
        "docs/input/原型/frontend/vite.config.mjs",
        "docs/input/原型/frontend/vite.config.mts",
        "docs/input/原型/frontend/vite.config.ts",
        "docs/input/原型/frontend/vitest.config.js",
        "docs/input/原型/frontend/vitest.config.mjs",
        "docs/input/原型/frontend/vitest.config.ts",
        "docs/input/原型/frontend/wdio.conf.js",
        "docs/input/原型/frontend/wdio.conf.ts"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "“微前端”“网关”“各微服务”等注释是原型假设。",
      "evidenceLinks": [
        "docs/input/原型/frontend/.env.example",
        "docs/input/原型/frontend/.prettierrc.json",
        "docs/input/原型/frontend/index.html",
        "docs/input/原型/frontend/tsconfig.app.json",
        "docs/input/原型/frontend/tsconfig.json",
        "docs/input/原型/frontend/tsconfig.node.json",
        "docs/input/原型/frontend/vite.config.ts"
      ],
      "metrics": {
        "regularFileByteCount": 2387,
        "regularFileCount": 7,
        "scope": "recursive-regular-files"
      },
      "owner": "UNKNOWN",
      "ownerStory": "1.1c",
      "pathOrSource": "docs/input/原型/frontend/.env.example; docs/input/原型/frontend/.prettierrc.json; docs/input/原型/frontend/index.html; docs/input/原型/frontend/tsconfig.app.json; docs/input/原型/frontend/tsconfig.json; docs/input/原型/frontend/tsconfig.node.json; docs/input/原型/frontend/vite.config.ts",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "reference-only",
      "risk": "原型假设不得覆盖 AD-1 模块化单体与 AD-28 生产基线。",
      "snapshotOnly": false,
      "type": "prototype-configuration",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:17ee67ca4f9d5c0a8d46c6f376b8c69038826b41b1745cec2ce50f00527e4329"
    },
    {
      "assetId": "prototype.dist",
      "checkedPaths": [
        "docs/input/原型/frontend/dist"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "已生成 dist 只记录摘要与规模，不可提升。",
      "evidenceLinks": [
        "docs/input/原型/frontend/dist"
      ],
      "metrics": {
        "regularFileByteCount": 2366521,
        "regularFileCount": 55,
        "scope": "recursive-regular-files"
      },
      "owner": "UNKNOWN",
      "ownerStory": "1.1d",
      "pathOrSource": "docs/input/原型/frontend/dist",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "replace",
      "risk": "无可复现构建、digest、签名或扫描证据。",
      "snapshotOnly": false,
      "type": "generated-build-output",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:c55cf119f6d6487eb94f909bfb83957007509a47106d8f48af9feb6d7f260489"
    },
    {
      "assetId": "prototype.layouts",
      "checkedPaths": [
        "docs/input/原型/frontend/src/layout",
        "docs/input/原型/frontend/src/layouts"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "布局与交互意图可作为迁移参考。",
      "evidenceLinks": [
        "docs/input/原型/frontend/src/layouts"
      ],
      "metrics": {
        "regularFileByteCount": 8778,
        "regularFileCount": 2,
        "scope": "recursive-regular-files"
      },
      "owner": "UNKNOWN",
      "ownerStory": "1.1b",
      "pathOrSource": "docs/input/原型/frontend/src/layouts",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "migrate",
      "risk": "未经过生产宿主、WebView 与无障碍验收。",
      "snapshotOnly": false,
      "type": "layout",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:3d3815f55ab9deddba118f7aa0baf049bc3a2b86218a8de5b1f6864b87077c15"
    },
    {
      "assetId": "prototype.lockfile",
      "checkedPaths": [
        "docs/input/原型/frontend/package-lock.json"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "仅用于解析原型实际版本。",
      "evidenceLinks": [
        "docs/input/原型/frontend/package-lock.json"
      ],
      "metrics": {
        "regularFileByteCount": 71977,
        "regularFileCount": 1,
        "scope": "recursive-regular-files"
      },
      "owner": "UNKNOWN",
      "ownerStory": "1.1c",
      "pathOrSource": "docs/input/原型/frontend/package-lock.json",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "reference-only",
      "risk": "不得作为未来生产 lockfile 提升。",
      "snapshotOnly": false,
      "type": "dependency-lock-snapshot",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:455c859fa387a2289af913c68dc6832f462ebdb1c6dd42a1996a3d94cd6b9640"
    },
    {
      "assetId": "prototype.mocks",
      "checkedPaths": [
        "docs/input/原型/frontend/src/mock",
        "docs/input/原型/frontend/src/mocks"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "mock 行为必须由真实端口/契约替换。",
      "evidenceLinks": [
        "docs/input/原型/frontend/src/mock"
      ],
      "metrics": {
        "regularFileByteCount": 37298,
        "regularFileCount": 10,
        "scope": "recursive-regular-files"
      },
      "owner": "UNKNOWN",
      "ownerStory": "1.1b",
      "pathOrSource": "docs/input/原型/frontend/src/mock",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "replace",
      "risk": "把 mock 当真实性会绕过授权、失败与审计语义。",
      "snapshotOnly": false,
      "type": "mock-behavior",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:1fb05ee27a8e0eec02ac7f33ffe07a65e0f49f5ed1f9245c1e3c5ef2354fbad0"
    },
    {
      "assetId": "prototype.node-modules",
      "checkedPaths": [
        "docs/input/原型/frontend/node_modules"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "node_modules 只记录摘要与规模，不作依赖分发源。",
      "evidenceLinks": [
        "docs/input/原型/frontend/node_modules"
      ],
      "metrics": {
        "scope": "top-level-entries",
        "topLevelEntryCount": 84,
        "topLevelRegularFileByteCount": 49553
      },
      "owner": "UNKNOWN",
      "ownerStory": "1.1d",
      "pathOrSource": "docs/input/原型/frontend/node_modules",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "replace",
      "risk": "安装树可能含缓存、平台差异与未受控传递依赖。",
      "snapshotOnly": true,
      "type": "installed-dependency-tree",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:60007ddf2ac200177bceb764f13644f21640f63132178940e5f6a54940ded532"
    },
    {
      "assetId": "prototype.package-manifest",
      "checkedPaths": [
        "docs/input/原型/frontend/package.json"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "Vite 5 / Vue 3 原型；semver 声明只作为差异输入。",
      "evidenceLinks": [
        "docs/input/原型/frontend/package.json"
      ],
      "metrics": {
        "regularFileByteCount": 819,
        "regularFileCount": 1,
        "scope": "recursive-regular-files"
      },
      "owner": "UNKNOWN",
      "ownerStory": "1.1c",
      "pathOrSource": "docs/input/原型/frontend/package.json",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "reference-only",
      "risk": "范围声明不是 PAB 精确 lock。",
      "snapshotOnly": false,
      "type": "dependency-manifest",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:144bbbdc0be6566efd04aa2f87a044a6f98dc3b270a61f4442b51d4c126861e9"
    },
    {
      "assetId": "prototype.routes",
      "checkedPaths": [
        "docs/input/原型/frontend/src/router",
        "docs/input/原型/frontend/src/routes"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "路由意图可迁移，行为须重新验证。",
      "evidenceLinks": [
        "docs/input/原型/frontend/src/router"
      ],
      "metrics": {
        "regularFileByteCount": 6095,
        "regularFileCount": 1,
        "scope": "recursive-regular-files"
      },
      "owner": "UNKNOWN",
      "ownerStory": "1.1b",
      "pathOrSource": "docs/input/原型/frontend/src/router",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "migrate",
      "risk": "不得继承原型认证与授权假设。",
      "snapshotOnly": false,
      "type": "routing",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:29f42137e81dfefcb4f12789636538be4bbad11b5d15742b62ad1a1a5ed5b4b7"
    },
    {
      "assetId": "prototype.source",
      "checkedPaths": [
        "docs/input/原型/frontend/src"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "UX 与页面意图仅作为迁移输入。",
      "evidenceLinks": [
        "docs/input/原型/frontend/src"
      ],
      "metrics": {
        "regularFileByteCount": 285538,
        "regularFileCount": 54,
        "scope": "recursive-regular-files"
      },
      "owner": "UNKNOWN",
      "ownerStory": "1.1b",
      "pathOrSource": "docs/input/原型/frontend/src",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "migrate",
      "risk": "mock/auth/store/API 行为不是生产真实性。",
      "snapshotOnly": false,
      "type": "source-code",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:db521ec80effaa68ede1e4b50316f8536c14f728a74f420ae9dbc45a14d2ad08"
    },
    {
      "assetId": "prototype.static-assets",
      "checkedPaths": [
        "docs/input/原型/frontend/public",
        "docs/input/原型/frontend/src/assets"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "静态资产仅作为设计参考。",
      "evidenceLinks": [
        "docs/input/原型/frontend/public"
      ],
      "metrics": {
        "regularFileByteCount": 945,
        "regularFileCount": 1,
        "scope": "recursive-regular-files"
      },
      "owner": "UNKNOWN",
      "ownerStory": "1.1b",
      "pathOrSource": "docs/input/原型/frontend/public",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "reference-only",
      "risk": "来源、许可证和生产优化状态未核验。",
      "snapshotOnly": false,
      "type": "static-assets",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:e66cc5e2b62a133404d570c899f65b466ede881c0fe74aa257e29d24111519fc"
    },
    {
      "assetId": "prototype.stores",
      "checkedPaths": [
        "docs/input/原型/frontend/src/store",
        "docs/input/原型/frontend/src/stores"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "仅迁移必要的页面状态意图。",
      "evidenceLinks": [
        "docs/input/原型/frontend/src/stores"
      ],
      "metrics": {
        "regularFileByteCount": 1686,
        "regularFileCount": 2,
        "scope": "recursive-regular-files"
      },
      "owner": "UNKNOWN",
      "ownerStory": "1.1b",
      "pathOrSource": "docs/input/原型/frontend/src/stores",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "migrate",
      "risk": "Pinia 2 和持久化行为不得成为生产基线。",
      "snapshotOnly": false,
      "type": "client-state",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:68fda0e820c1c9b99979bc746db176e51a0a1adc789bdc4b65b26a839c0485e1"
    },
    {
      "assetId": "prototype.styles",
      "checkedPaths": [
        "docs/input/原型/frontend/src/style.css",
        "docs/input/原型/frontend/src/styles"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "视觉意图可迁移。",
      "evidenceLinks": [
        "docs/input/原型/frontend/src/styles"
      ],
      "metrics": {
        "regularFileByteCount": 2535,
        "regularFileCount": 1,
        "scope": "recursive-regular-files"
      },
      "owner": "UNKNOWN",
      "ownerStory": "1.1b",
      "pathOrSource": "docs/input/原型/frontend/src/styles",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "migrate",
      "risk": "尚无视觉回归、WCAG 2.2 AA 或宿主适配证据。",
      "snapshotOnly": false,
      "type": "styles",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:0b44c62b616f13084de52e5ed6f28fe41f73530bc1405efa235c4f70bcb5c4a7"
    },
    {
      "assetId": "prototype.types",
      "checkedPaths": [
        "docs/input/原型/frontend/src/types"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "类型名称只作契约发现输入。",
      "evidenceLinks": [
        "docs/input/原型/frontend/src/types"
      ],
      "metrics": {
        "regularFileByteCount": 7275,
        "regularFileCount": 1,
        "scope": "recursive-regular-files"
      },
      "owner": "UNKNOWN",
      "ownerStory": "1.1b",
      "pathOrSource": "docs/input/原型/frontend/src/types",
      "present": true,
      "readStatus": "readable",
      "recommendedDisposition": "migrate",
      "risk": "原型类型不能替代受控 OpenAPI/事件 schema。",
      "snapshotOnly": false,
      "type": "typescript-types",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "sha256:42b15322a3a2f2c15c9185196378256b07ca7265674bcc8fdf3ad8b63856e0e4"
    },
    {
      "assetId": "workspace.ci-definitions",
      "checkedPaths": [
        ".buildkite",
        ".circleci",
        ".drone.yml",
        ".github/workflows",
        ".gitlab-ci.yml",
        ".travis.yaml",
        ".travis.yml",
        "Jenkinsfile",
        "appveyor.yaml",
        "appveyor.yml",
        "azure-pipelines.yml",
        "bitbucket-pipelines.yml"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "未发现 CI 定义；缺失本身是供应链证据事实。",
      "evidenceLinks": [
        "audit://absence/workspace.ci-definitions"
      ],
      "owner": "UNKNOWN",
      "ownerStory": "1.1d",
      "pathOrSource": ".github/workflows、GitLab/Jenkins/Azure/CircleCI/Travis/Bitbucket/Buildkite/Drone（未发现）",
      "present": false,
      "readStatus": "missing",
      "recommendedDisposition": "replace",
      "risk": "不得推断已执行构建、扫描、SBOM、provenance 或签名。",
      "snapshotOnly": false,
      "type": "ci-metadata",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "UNAVAILABLE"
    },
    {
      "assetId": "workspace.contract-definitions",
      "checkedPaths": [
        "asyncapi.json",
        "asyncapi.yaml",
        "asyncapi.yml",
        "contracts",
        "openapi.json",
        "openapi.yaml",
        "openapi.yml",
        "schema.json",
        "swagger.json",
        "swagger.yaml",
        "swagger.yml"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "OpenAPI、事件与 Schema 候选必须显式盘点并追溯来源。",
      "evidenceLinks": [
        "audit://absence/workspace.contract-definitions"
      ],
      "owner": "UNKNOWN",
      "ownerStory": "1.1b",
      "pathOrSource": "contracts/ 或根级 OpenAPI、AsyncAPI、JSON Schema（未发现）",
      "present": false,
      "readStatus": "missing",
      "recommendedDisposition": "reference-only",
      "risk": "现存文件不自动等同于已批准的 PAB/PIC 契约或运行兼容性证据。",
      "snapshotOnly": false,
      "type": "api-and-event-contracts",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "UNAVAILABLE"
    },
    {
      "assetId": "workspace.deployment-engineering",
      "checkedPaths": [
        "Containerfile",
        "Dockerfile",
        "compose.yaml",
        "compose.yml",
        "deploy",
        "docker-compose.yaml",
        "docker-compose.yml",
        "helm",
        "infra",
        "k8s",
        "kubernetes",
        "manifests",
        "terragrunt.hcl"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "未发现容器、部署或基础设施工程。",
      "evidenceLinks": [
        "audit://absence/workspace.deployment-engineering"
      ],
      "owner": "UNKNOWN",
      "ownerStory": "1.1d",
      "pathOrSource": "deploy/、容器/Compose、Terraform、Helm、Kubernetes 或一级项目部署指纹（未发现）",
      "present": false,
      "readStatus": "missing",
      "recommendedDisposition": "replace",
      "risk": "不得虚构部署平台、构建镜像 digest 或制品库。",
      "snapshotOnly": false,
      "type": "deployment-metadata",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "UNAVAILABLE"
    },
    {
      "assetId": "workspace.git-repository",
      "checkedPaths": [
        ".git"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "工作区根未发现 Git 元数据；远程、分支与提交均不可核验。",
      "evidenceLinks": [
        "audit://absence/workspace.git-repository"
      ],
      "owner": "UNKNOWN",
      "ownerStory": "1.1b",
      "pathOrSource": ".git（未发现）",
      "present": false,
      "readStatus": "missing",
      "recommendedDisposition": "reference-only",
      "risk": "缺失版本历史与远程来源证据，禁止声称仓库核验通过。",
      "snapshotOnly": false,
      "type": "repository-metadata",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "UNAVAILABLE"
    },
    {
      "assetId": "workspace.production-backend",
      "checkedPaths": [
        "backend",
        "build.gradle",
        "build.gradle.kts",
        "database/migrations",
        "db/migrations",
        "gradlew",
        "migrations",
        "mvnw",
        "pom.xml",
        "settings.gradle",
        "settings.gradle.kts"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "未发现生产后端或数据库迁移工程。",
      "evidenceLinks": [
        "audit://absence/workspace.production-backend"
      ],
      "owner": "UNKNOWN",
      "ownerStory": "1.1b",
      "pathOrSource": "backend/、根级或一级项目中的 Maven/Gradle 指纹（未发现）",
      "present": false,
      "readStatus": "missing",
      "recommendedDisposition": "replace",
      "risk": "Architecture 的 backend/ 是目标 seed，不是可复用现状。",
      "snapshotOnly": false,
      "type": "production-backend",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "UNAVAILABLE"
    },
    {
      "assetId": "workspace.production-frontend",
      "checkedPaths": [
        "frontend"
      ],
      "classification": "unknown-blocked",
      "dispositionReason": "生产前端候选必须作为独立资产审计，不得只隐含在工作区摘要中。",
      "evidenceLinks": [
        "audit://absence/workspace.production-frontend"
      ],
      "owner": "UNKNOWN",
      "ownerStory": "1.1b",
      "pathOrSource": "frontend/ 或一级前端项目指纹（未发现）",
      "present": false,
      "readStatus": "missing",
      "recommendedDisposition": "migrate",
      "risk": "owner、来源与生产真实性尚未闭合，需由 1.1b 建立受控工程边界。",
      "snapshotOnly": false,
      "type": "production-frontend",
      "verifiedDate": "2026-07-18",
      "versionOrDigest": "UNAVAILABLE"
    }
  ],
  "auditVersion": "2.9.0",
  "controlledInputDigest": "sha256:bd0c160dd756150af7bee55c2616170ebed0ea6752af5fe15dcb66989d1980a5",
  "executionEvidence": {
    "acceptanceEvidenceLocation": "Story Dev Agent Record",
    "generateCommand": "python3 /Users/hei/project/ScholarSense-bmad-method/_bmad/scripts/audit_production_assets.py generate --workspace-root /Users/hei/project/ScholarSense-bmad-method --prototype-root 'docs/input/原型/frontend' --architecture-source _bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md --pab-source _bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md --inventory-output _bmad-output/implementation-artifacts/1-1a-production-asset-inventory.md --schema-output _bmad-output/implementation-artifacts/1-1a-production-asset-inventory.schema.json --gap-output _bmad-output/implementation-artifacts/1-1a-prototype-pab-gap.md --generated-at 2026-07-18T20:48:16+08:00 --force",
    "inputScope": [
      ".buildkite",
      ".circleci",
      ".drone.yml",
      ".git",
      ".github/workflows",
      ".gitlab-ci.yml",
      ".nvmrc",
      ".travis.yaml",
      ".travis.yml",
      "Containerfile",
      "Dockerfile",
      "Jenkinsfile",
      "_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md",
      "_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md",
      "appveyor.yaml",
      "appveyor.yml",
      "asyncapi.json",
      "asyncapi.yaml",
      "asyncapi.yml",
      "azure-pipelines.yml",
      "backend",
      "bitbucket-pipelines.yml",
      "build.gradle",
      "build.gradle.kts",
      "compose.yaml",
      "compose.yml",
      "contracts",
      "database/migrations",
      "db/migrations",
      "deploy",
      "docker-compose.yaml",
      "docker-compose.yml",
      "docs/input/原型/frontend/.env.example",
      "docs/input/原型/frontend/.eslintrc",
      "docs/input/原型/frontend/.eslintrc.cjs",
      "docs/input/原型/frontend/.eslintrc.js",
      "docs/input/原型/frontend/.eslintrc.json",
      "docs/input/原型/frontend/.nvmrc",
      "docs/input/原型/frontend/.prettierrc",
      "docs/input/原型/frontend/.prettierrc.js",
      "docs/input/原型/frontend/.prettierrc.json",
      "docs/input/原型/frontend/cypress.config.js",
      "docs/input/原型/frontend/cypress.config.mjs",
      "docs/input/原型/frontend/cypress.config.ts",
      "docs/input/原型/frontend/dist",
      "docs/input/原型/frontend/eslint.config.js",
      "docs/input/原型/frontend/eslint.config.mjs",
      "docs/input/原型/frontend/eslint.config.ts",
      "docs/input/原型/frontend/index.html",
      "docs/input/原型/frontend/jest.config.js",
      "docs/input/原型/frontend/jest.config.ts",
      "docs/input/原型/frontend/node_modules",
      "docs/input/原型/frontend/package-lock.json",
      "docs/input/原型/frontend/package.json",
      "docs/input/原型/frontend/playwright.config.js",
      "docs/input/原型/frontend/playwright.config.mjs",
      "docs/input/原型/frontend/playwright.config.ts",
      "docs/input/原型/frontend/postcss.config.cjs",
      "docs/input/原型/frontend/postcss.config.js",
      "docs/input/原型/frontend/postcss.config.mjs",
      "docs/input/原型/frontend/postcss.config.ts",
      "docs/input/原型/frontend/prettier.config.js",
      "docs/input/原型/frontend/prettier.config.mjs",
      "docs/input/原型/frontend/public",
      "docs/input/原型/frontend/src",
      "docs/input/原型/frontend/tailwind.config.cjs",
      "docs/input/原型/frontend/tailwind.config.js",
      "docs/input/原型/frontend/tailwind.config.mjs",
      "docs/input/原型/frontend/tailwind.config.ts",
      "docs/input/原型/frontend/tsconfig.app.json",
      "docs/input/原型/frontend/tsconfig.json",
      "docs/input/原型/frontend/tsconfig.node.json",
      "docs/input/原型/frontend/vite.config.js",
      "docs/input/原型/frontend/vite.config.mjs",
      "docs/input/原型/frontend/vite.config.mts",
      "docs/input/原型/frontend/vite.config.ts",
      "docs/input/原型/frontend/vitest.config.js",
      "docs/input/原型/frontend/vitest.config.mjs",
      "docs/input/原型/frontend/vitest.config.ts",
      "docs/input/原型/frontend/wdio.conf.js",
      "docs/input/原型/frontend/wdio.conf.ts",
      "frontend",
      "gradlew",
      "helm",
      "infra",
      "k8s",
      "kubernetes",
      "manifests",
      "migrations",
      "mvnw",
      "openapi.json",
      "openapi.yaml",
      "openapi.yml",
      "pom.xml",
      "schema.json",
      "settings.gradle",
      "settings.gradle.kts",
      "swagger.json",
      "swagger.yaml",
      "swagger.yml",
      "terragrunt.hcl"
    ],
    "replayCommand": "python3 /Users/hei/project/ScholarSense-bmad-method/_bmad/scripts/audit_production_assets.py check --workspace-root /Users/hei/project/ScholarSense-bmad-method --prototype-root 'docs/input/原型/frontend' --architecture-source _bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md --pab-source _bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md --inventory-output _bmad-output/implementation-artifacts/1-1a-production-asset-inventory.md --schema-output _bmad-output/implementation-artifacts/1-1a-production-asset-inventory.schema.json --gap-output _bmad-output/implementation-artifacts/1-1a-prototype-pab-gap.md",
    "result": "passed",
    "resultScope": "generation-and-in-memory-validation",
    "scriptSha256": "sha256:a8d0a48fceb74644d24fe142aea055890d21ec6b2267096b71bbefcab2fab219",
    "scriptVersion": "2.9.0",
    "steps": [
      {
        "command": "python3 /Users/hei/project/ScholarSense-bmad-method/_bmad/scripts/audit_production_assets.py generate --workspace-root /Users/hei/project/ScholarSense-bmad-method --prototype-root 'docs/input/原型/frontend' --architecture-source _bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md --pab-source _bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md --inventory-output _bmad-output/implementation-artifacts/1-1a-production-asset-inventory.md --schema-output _bmad-output/implementation-artifacts/1-1a-production-asset-inventory.schema.json --gap-output _bmad-output/implementation-artifacts/1-1a-prototype-pab-gap.md --generated-at 2026-07-18T20:48:16+08:00 --force",
        "exitCode": 0,
        "name": "generate",
        "status": "passed"
      },
      {
        "command": "in-memory published JSON Schema validation",
        "exitCode": 0,
        "name": "schema-validation",
        "status": "passed"
      },
      {
        "command": "in-memory dependency gap contract validation",
        "exitCode": 0,
        "name": "gap-validation",
        "status": "passed"
      }
    ],
    "unresolvedCount": 20
  },
  "generatedAt": "2026-07-18T20:48:16+08:00",
  "generationId": "sha256:08ab3ff93fa9423be45dfd980d70a8047414172314183a8f614599deca743181",
  "schemaVersion": "3.2.0",
  "unresolvedItems": [
    {
      "assetId": "prototype.components",
      "impact": "原型组件与 PAB 依赖组合未验证。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1b"
    },
    {
      "assetId": "prototype.config",
      "impact": "原型假设不得覆盖 AD-1 模块化单体与 AD-28 生产基线。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1c"
    },
    {
      "assetId": "prototype.dist",
      "impact": "无可复现构建、digest、签名或扫描证据。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1d"
    },
    {
      "assetId": "prototype.layouts",
      "impact": "未经过生产宿主、WebView 与无障碍验收。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1b"
    },
    {
      "assetId": "prototype.lockfile",
      "impact": "不得作为未来生产 lockfile 提升。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1c"
    },
    {
      "assetId": "prototype.mocks",
      "impact": "把 mock 当真实性会绕过授权、失败与审计语义。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1b"
    },
    {
      "assetId": "prototype.node-modules",
      "impact": "安装树可能含缓存、平台差异与未受控传递依赖。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1d"
    },
    {
      "assetId": "prototype.package-manifest",
      "impact": "范围声明不是 PAB 精确 lock。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1c"
    },
    {
      "assetId": "prototype.routes",
      "impact": "不得继承原型认证与授权假设。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1b"
    },
    {
      "assetId": "prototype.source",
      "impact": "mock/auth/store/API 行为不是生产真实性。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1b"
    },
    {
      "assetId": "prototype.static-assets",
      "impact": "来源、许可证和生产优化状态未核验。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1b"
    },
    {
      "assetId": "prototype.stores",
      "impact": "Pinia 2 和持久化行为不得成为生产基线。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1b"
    },
    {
      "assetId": "prototype.styles",
      "impact": "尚无视觉回归、WCAG 2.2 AA 或宿主适配证据。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1b"
    },
    {
      "assetId": "prototype.types",
      "impact": "原型类型不能替代受控 OpenAPI/事件 schema。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1b"
    },
    {
      "assetId": "workspace.ci-definitions",
      "impact": "不得推断已执行构建、扫描、SBOM、provenance 或签名。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1d"
    },
    {
      "assetId": "workspace.contract-definitions",
      "impact": "现存文件不自动等同于已批准的 PAB/PIC 契约或运行兼容性证据。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1b"
    },
    {
      "assetId": "workspace.deployment-engineering",
      "impact": "不得虚构部署平台、构建镜像 digest 或制品库。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1d"
    },
    {
      "assetId": "workspace.git-repository",
      "impact": "缺失版本历史与远程来源证据，禁止声称仓库核验通过。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1b"
    },
    {
      "assetId": "workspace.production-backend",
      "impact": "Architecture 的 backend/ 是目标 seed，不是可复用现状。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1b"
    },
    {
      "assetId": "workspace.production-frontend",
      "impact": "owner、来源与生产真实性尚未闭合，需由 1.1b 建立受控工程边界。",
      "issue": "当前 owner 或来源/版本证据未闭合。",
      "ownerStory": "1.1b"
    }
  ],
  "verificationDate": "2026-07-18"
}
```
<!-- asset-inventory-json:end -->

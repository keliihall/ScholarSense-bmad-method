---
title: 学林知微学校 App 适用性基线
status: approved
version: AAB-1.0.0
authorityRecordId: USER-2026-07-19-SCHOOL-APP-NA
approvedBy: Hei
approvalRole: 项目总负责人与委托决策人
approvalDate: 2026-07-19
effectiveDate: 2026-07-19
scope:
  - Story-1.1c
  - Story-1.1d
applicability: not-applicable
runtimeEvidenceClaim: none
supersedesForScope:
  - delegated-decision-baseline-2026-07-17.md#9-PABPPAP-100 中 1.1c/1.1d 的 App/WebView 报告要求
  - ARCHITECTURE-SPINE.md#AD-28 中 1.1d 的 WebView 报告要求
---

# 学林知微学校 App 适用性基线 AAB-1.0.0

## 裁决

Hei 于 2026-07-19 批准：学校 App owner 基线及 App/WebView 运行报告对 Story 1.1c、1.1d 为 `not-applicable`。这两个 Story 的环境/发布清单必须保存：

- `appApplicabilityBaselineVersion=AAB-1.0.0`
- `appApplicabilityBaselineSha256=<本受控文件在 release source commit 中的 SHA-256>`
- `decisionId=USER-2026-07-19-SCHOOL-APP-NA`
- `approvedBy=Hei`
- `effectiveAt=2026-07-19`
- `applicability=not-applicable`
- `runtimeEvidenceClaim=none`

不得填造 iOS/Android、WebView/WKWebView、宿主或设备版本，不得用桌面 Chromium、375px 响应式视口或 Web 报告冒充真机通过。

## 范围边界

- Story 1.1d 仍必须生成绑定最终待提升前端制品摘要的 Web 浏览器、视觉与无障碍正式报告；AAB-1.0.0 不削弱 Web 发布门。
- 本裁决只收窄 Story 1.1c/1.1d 的 App/WebView 基线和运行报告责任，不取消 PRD 的统一 App 产品范围，不把 FR-55、NFR-18/NFR-29/NFR-31/NFR-34 或 Epic 7 写成已通过。
- NFR-31 与真实 App/WebView/真机验收的最终 owner 仍为 Story 7.1/7.x。未来启用学校 App 时必须取得真实 App owner 基线，发布新的 AAB/PAB/TEST-ENV/ReleaseManifest 版本，并由 7.1/7.x 生成真机证据。
- AAB-1.0.0 与其历史 ReleaseManifest 不得原位覆盖；任何适用性变化均产生新版本并保留本 N/A 历史。

## 规划同步范围

本基线是 2026-07-17 委托决策基线的后续受控 companion；冲突仅在上述 scope 内由 AAB-1.0.0 覆盖。Architecture、UX、Epics、requirements traceability 与 Story 1.1d 必须显式引用本版本和 decision digest，不得只引用实施层备注。

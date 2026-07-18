# ADR：PerformanceProfileVersion PP-1.0.0 / AvailabilityPolicyVersion AP-1.0.0

- Status: approved
- Owner: frontend-performance-owner
- Approved by: Hei
- Effective at: 2026-07-19T00:00:00+08:00
- Authority: AUTH-2026-07-17-001 / AD-22 / PP-1.0.0 / AP-1.0.0 / UXB-1.0.0

## 权威文件

- `contracts/performance/performance-profile.schema.json` 与 `performance-profile-pp-1.0.0.json`
- `contracts/performance/availability-policy-ap-1.0.0.json`
- `contracts/performance/test-environment.schema.json` 与 `test-environment-1.0.0.json`
- `contracts/performance/frontend-baseline.schema.json` 与 `frontend-baseline-1.0.0.json`

JSON manifest 是机器执行权威源，本 ADR 解释版本和证据边界。任何值或摘要变化都要求新 manifest/version，checker 拒绝静默漂移。

## 用户感知 SLI

`ui.content-ready` 只在必需数据、当前授权投影、关键控件和可访问名称全部完成时发出；骨架屏、空容器或仅网关完成不算。工作台/列表/详情 P95 `≤2000ms`。

Filter-ready 从用户操作到图表、等价表格和辅助技术反馈全部更新，P95 `≤3000ms`。`ui.state-observed` 从服务端 `committedAt` 到另一在线端应用同一或更高 `aggregateVersion`，P95 `≤5000ms`。所有起止点使用 NTP 偏差 `≤100ms` 的服务端同步时钟。

诊断必须保留 host/navigation、gateway、query、serialization、network、parse、render/main-thread、accessibility-ready、cache-state 分段。失败样本保留并标明归因，不能从分母删除。附加 p75 guardrail 为 LCP `≤2500ms`、INP `≤200ms`、CLS `≤0.1`。

## 可用性口径

Asia/Shanghai 每日 07:00—23:00，校园有线、校园 Wi-Fi、外部移动网三个探针每 60 秒依次执行 SSO 后首页、列表、详情、安全幂等命令/read-back。每步具有独立成功判定和超时，任一步失败即该探针该分钟失败；恰好三个探针且至少两个成功才为 good minute。月度 SLI=`good eligible minutes / eligible minutes`；采样缺口计 bad，计划维护和外部依赖仍在分母并单列，完整自然月目标 `≥99.9%`。

本 Story 只交付契约与 fixture，不生成或暗示完整自然月结果。

## 环境与移动 N/A 决策

Web 矩阵不再只保存发布页自摘要：目标 OS 固定为 `macOS 26.5.2 build 25F84 arm64`，Chrome for Testing mac-arm64 镜像固定为 `150.0.7871.124`（artifact `36c8b5...87a47`、executable `22ddf3...7284`）与 `149.0.7827.155`（artifact `135b69...f36c7`、executable `e9c22e...f01d`），Edge macOS universal pkg 固定为 `150.0.4078.65`（artifact `68929c...fd86`、executable `d82cb1...64bb`）与 `149.0.4022.98`（artifact `0165f1...b9d5`、executable `e7da6f...8af`）。Checker 固定完整 URL/SHA，品牌 preflight 必须校验下载镜像、实际可执行文件、目标 OS 与当前源码清洁构建；正式品牌报告仍归 1.1d。用户 Hei 于 2026-07-19 明确批准学校 App owner 基线“全部跳过，不适用”；环境 manifest 因而保存 `not-applicable`、批准人、日期和理由，不填造 iOS/Android 宿主、WebView/WKWebView 或设备值，也不声称 7.x 真机验收通过。375px 仍作为响应式 baseline fixture 执行。

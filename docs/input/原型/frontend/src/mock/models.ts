import type { ComplianceGate, ModelVersion } from '@/types';

export const modelVersions: ModelVersion[] = [
  {
    version: 'guanbian-v1.4',
    mirror: '观变镜（个人基线/异常检测）',
    precisionAtK: 0.82,
    recallAtK: 0.71,
    noiseRate: 0.18,
    fairness: '本/研噪声差异 3.1%，可接受',
    complianceCheck: '通过',
    releaseStatus: '生产',
    rollbackBaseline: 'guanbian-v1.3',
  },
  {
    version: 'hezheng-v0.9',
    mirror: '合证镜（Top-k 合证排序）',
    precisionAtK: 0.79,
    recallAtK: 0.68,
    noiseRate: 0.21,
    fairness: '实习群体噪声偏高 5.4%，整改中',
    complianceCheck: '待复核',
    releaseStatus: '灰度',
    rollbackBaseline: '规则权重版',
  },
  {
    version: 'guanbian-v1.5',
    mirror: '观变镜（候选）',
    precisionAtK: 0.85,
    recallAtK: 0.73,
    noiseRate: 0.15,
    fairness: '评估中',
    complianceCheck: '待复核',
    releaseStatus: '灰度',
    rollbackBaseline: 'guanbian-v1.4',
  },
];

/** 模型治理四关卡（PRD 5.5） */
export const modelGates = [
  { name: '数据质量', desc: '主键、连续性、覆盖率；异常源自动熔断', pass: true },
  { name: '效果评估', desc: 'Precision@K / Recall@K / 噪声率 / 协同成效', pass: true },
  { name: '分群公平性', desc: '本科/研究生/实习等噪声差异需解释整改', pass: false },
  { name: '合规与人工复核', desc: '影响评估、授权范围、回退方案、责任确认', pass: false },
];

/** 合规检查关卡（PRD 5.6 / M16） */
export const complianceGates: ComplianceGate[] = [
  {
    step: '1 · 必要性论证',
    desc: '说明处理目的、范围与最小必要性',
    status: '已完成',
    owner: '学工处 / 法务',
    updatedAt: '2026-05-10',
  },
  {
    step: '2 · 个人信息保护影响评估（PIPIA）',
    desc: '识别风险、评估影响、给出缓解措施',
    status: '进行中',
    owner: '合规 / 法务',
    updatedAt: '2026-06-18',
  },
  {
    step: '3 · 授权与脱敏配置',
    desc: '角色 × 数据范围 × 字段脱敏落地',
    status: '已完成',
    owner: '运维 / 学工处',
    updatedAt: '2026-06-12',
  },
  {
    step: '4 · 审计与回退验证',
    desc: '关键操作留痕、一键回退演练通过',
    status: '待开始',
    owner: '运维',
    updatedAt: '—',
  },
];

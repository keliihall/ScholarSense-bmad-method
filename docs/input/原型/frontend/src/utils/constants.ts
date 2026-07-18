// 枚举 → 文案 / 配色 的统一映射。所有标签颜色集中在此，保证全站一致。
import type { CareLevel, ClueCategory, ClueStatus, IdentityTag, RoleKey } from '@/types';

/** 关怀等级：Ⅰ 提醒 / Ⅱ 重点 / Ⅲ 重点关怀（PRD 5.3） */
export const CARE_LEVEL: Record<
  CareLevel,
  { label: string; short: string; color: string; tag: string }
> = {
  I: { label: 'Ⅰ级 · 提醒', short: 'Ⅰ', color: '#1f9d72', tag: 'success' },
  II: { label: 'Ⅱ级 · 重点', short: 'Ⅱ', color: '#e6a23c', tag: 'warning' },
  III: { label: 'Ⅲ级 · 重点关怀', short: 'Ⅲ', color: '#e5523f', tag: 'danger' },
};

/** 处置、时限与分级阈值（对齐老师方案：≥6 Ⅰ / ≥9 Ⅱ / ≥12 Ⅲ） */
export const CARE_LEVEL_ACTION: Record<
  CareLevel,
  { action: string; limit: string; threshold: number }
> = {
  I: { action: '谈心谈话、关心了解', limit: '常规跟进', threshold: 6 },
  II: { action: '介入干预、持续跟踪', limit: '48 小时内', threshold: 9 },
  III: { action: '紧急处置、多方联动', limit: '24 小时内', threshold: 12 },
};

/** 风险预警分级阈值（预警得分 = 行为得分 × 身份加权系数） */
export const LEVEL_THRESHOLD: Record<CareLevel, number> = { I: 6, II: 9, III: 12 };

/** 身份标签：加权系数与动态更新规则（老师方案 二·(三)） */
export const IDENTITY_TAG: Record<
  IdentityTag,
  { label: string; weight: number; color: string; tag: string; rule: string }
> = {
  普通: { label: '普通', weight: 1, color: '#6b7a8d', tag: 'info', rule: '默认身份，加权系数 ×1' },
  一般关注: {
    label: '一般关注',
    weight: 1.5,
    color: '#e6a23c',
    tag: 'warning',
    rule: '学期内 3 次预警 / 心理普测橙色 / 延长学年（需辅导员审核）+ 学工部·辅导员手动添加',
  },
  重点关注: {
    label: '重点关注',
    weight: 2,
    color: '#e5523f',
    tag: 'danger',
    rule: '学期内 5 次预警 / 心理普测红色（需辅导员审核）+ 学工部·辅导员手动添加',
  },
};

/** 差异化推送频率（老师方案 三·1） */
export const PUSH_FREQ: Record<ClueCategory, { calc: string; push: string }> = {
  academic: { calc: '成绩发布后', push: '每学期 2 次（期末 / 缓考后一周内）' },
  economic: { calc: '每月 1 日', push: '每月 1 次（月度报告）' },
  safety: { calc: '每日凌晨', push: '实时（每日汇总推送）' },
};

export const CLUE_CATEGORY: Record<ClueCategory, { label: string; color: string; icon: string }> = {
  academic: { label: '学业关怀', color: '#3b7cff', icon: 'Reading' },
  economic: { label: '经济关怀', color: '#16a394', icon: 'Wallet' },
  safety: { label: '安全关怀', color: '#e5523f', icon: 'FirstAidKit' },
};

export const CLUE_STATUS: Record<ClueStatus, { label: string; tag: string }> = {
  pending: { label: '待核实', tag: 'danger' },
  processing: { label: '处理中', tag: 'warning' },
  followed: { label: '已跟进', tag: 'success' },
  overdue: { label: '超期', tag: 'danger' },
  closed: { label: '已关闭', tag: 'info' },
  fused: { label: '已熔断', tag: 'info' },
};

/** 角色定义（PRD 第 2 章），含数据范围与高敏可见性 */
export const ROLES: Record<
  RoleKey,
  {
    name: string;
    desc: string;
    canViewSensitive: boolean;
    dataScope: 'all' | 'college' | 'own' | 'none';
  }
> = {
  counselor: { name: '辅导员', desc: '仅本人责任学生', canViewSensitive: true, dataScope: 'own' },
  college: {
    name: '学院管理端',
    desc: '本学院范围',
    canViewSensitive: false,
    dataScope: 'college',
  },
  affairs: {
    name: '学工管理端',
    desc: '全校业务 · 敏感按授权',
    canViewSensitive: true,
    dataScope: 'all',
  },
  leader: {
    name: '领导（驾驶舱）',
    desc: '默认仅汇总 · 按权下钻',
    canViewSensitive: false,
    dataScope: 'all',
  },
  collaborator: {
    name: '协同角色',
    desc: '心理/资助/教务/保卫 · 最小授权',
    canViewSensitive: true,
    dataScope: 'own',
  },
  ops: { name: '运维管理员', desc: '默认不看业务明细', canViewSensitive: false, dataScope: 'none' },
  data: { name: '数据责任人', desc: '本部门所供数据', canViewSensitive: false, dataScope: 'none' },
};

export const SCENES = ['毕业季专项', '隐性经济压力', '住宿安全', '夜间作息'] as const;

/** 全局合规横幅文案 */
export const COMPLIANCE_BANNER =
  '本系统输出为「需人工核实的线索」，不作自动决策；线索而非结论、建议而非自动决策、授权可见而非全员可查，全程留痕。';

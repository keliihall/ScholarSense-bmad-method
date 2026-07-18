// 学林知微 · 全局类型定义
// 说明：纯前端原型类型，字段对齐 doc/PRD.md 第 6 章数据实体；均为「需人工核实的线索」语义。

/** 角色（PRD 第 2 章七类角色） */
export type RoleKey =
  | 'counselor' // 辅导员
  | 'college' // 学院管理端
  | 'affairs' // 学工管理端（学工处）
  | 'leader' // 领导（驾驶舱）
  | 'collaborator' // 协同角色（心理/资助/教务/保卫/后勤）
  | 'ops' // 系统/运维管理员
  | 'data'; // 数据责任人

export interface Role {
  key: RoleKey;
  name: string;
  desc: string;
  /** 是否可见高敏字段（如心理标签） */
  canViewSensitive: boolean;
  /** 数据范围：all=全校 / college=本院 / own=责任学生 / none=不见业务明细 */
  dataScope: 'all' | 'college' | 'own' | 'none';
}

export interface CurrentUser {
  id: string;
  name: string;
  role: RoleKey;
  college?: string;
  /** 辅导员负责的学生数（演示用） */
  studentCount?: number;
  avatarText: string;
}

/** 关怀等级（PRD 5.3） */
export type CareLevel = 'I' | 'II' | 'III';

/** 身份标签（老师方案：决定风险得分的加权系数 ×1 / ×1.5 / ×2） */
export type IdentityTag = '普通' | '一般关注' | '重点关注';

/** 线索类别（三大类） */
export type ClueCategory = 'academic' | 'economic' | 'safety';

/** 线索状态 */
export type ClueStatus =
  | 'pending' // 待核实
  | 'processing' // 处理中
  | 'followed' // 已跟进
  | 'overdue' // 超期
  | 'closed' // 已关闭
  | 'fused'; // 已熔断

/** 培养层次 */
export type EduLevel = '本科' | '硕士' | '博士';

/** 个人基线对照项（观变镜：以本人历史正常区间为参照） */
export interface BaselineItem {
  dimension: string; // 维度（餐均消费/夜间在线时长/门禁活跃/学期绩点…）
  current: string; // 当前值
  baseline: string; // 个人基线
  change: string; // 偏移
  window: string; // 数据窗口（近 28/56 日等）
  status: 'normal' | 'watch' | 'alert'; // 达标/留意/异常
}

export interface Student {
  id: string; // 学号（演示脱敏）
  name: string;
  gender: '男' | '女';
  college: string;
  major: string;
  grade: string;
  eduLevel: EduLevel;
  className: string;
  status: '在读' | '休学' | '复学';
  counselor: string;
  phone: string; // 敏感：默认脱敏
  /** 身份标签（普通/一般关注/重点关注）：决定风险得分加权系数 */
  identityTag: IdentityTag;
  /** 关爱标签（含高敏，按授权可见） */
  tags: CareTag[];
  /** 学业/经济/安全 三维信号概览（0-100，越高越需关注） */
  signals: { academic: number; economic: number; safety: number };
  /** 个人基线对照（当前值 vs 本人历史正常区间） */
  baselines: BaselineItem[];
}

/** 证据项（PRD 6.4 Evidence） */
export interface Evidence {
  source: string; // 数据源
  metric: string; // 指标项
  current: string; // 当前值
  baseline: string; // 基线值
  change: string; // 变化幅度
  window: string; // 时间窗
  /** 场景排除命中情况（请假/实习/设备故障等） */
  exclusion: string;
  confidence: number; // 数据可信度 0-100
}

/** 核实反馈（PRD 6.4 Feedback） */
export interface Feedback {
  operator: string;
  time: string;
  contact: string; // 联系情况
  result: '属实' | '噪声' | '待观察';
  noiseReason?: string; // 噪声原因
  careWay?: string; // 关怀方式
  referred: boolean; // 是否转介
  keepWatch: boolean; // 持续关注
  note?: string;
}

/** 线索（PRD 6.4 Clue） */
export interface Clue {
  id: string;
  studentId: string;
  studentName: string;
  college: string;
  category: ClueCategory;
  level: CareLevel;
  /** 行为得分（命中规则赋分之和，未加权） */
  behaviorScore: number;
  /** 身份加权系数（普通1 / 一般关注1.5 / 重点关注2） */
  identityWeight: number;
  score: number; // 预警得分 = 行为得分 × 身份系数（Top-k 与分级用）
  independentCount: number; // 独立线索数
  confidence: number; // 数据可信度 0-100
  rule: string; // 触发规则
  scene: string; // 场景（毕业季/经济/住宿安全/夜间作息）
  createdAt: string;
  deadline: string; // 跟进截止
  status: ClueStatus;
  explanation: string; // AI 解释
  suggestion: string[]; // 关怀建议（路径）
  evidence: Evidence[];
  feedback?: Feedback;
}

/** 关爱标签 / 名单（PRD 6.3） */
export interface CareTag {
  type: '困难身份' | '心理关注' | '重点关爱' | '经济困难' | '学业预警';
  source: string;
  /** 是否高敏（如「心理关注」仅授权角色可见） */
  sensitive: boolean;
}

/** 规则（对齐老师方案「规则宽表」18 条） */
export interface Rule {
  seq: number; // 风险行为序号
  indicator: ClueCategory; // 风险预警指标：学业/经济/安全
  behaviorType: string; // 风险行为类别（挂科/绩点/用餐/晚归晚出…）
  rule: string; // 风险行为判定规则
  score: string; // 赋分规则（行为间不叠加）
  weighted: boolean; // 是否赋身份系数（经济类全体统一，不赋系数）
  pushTiming: string; // 推送时机 / 备注
  status: '草稿' | '灰度' | '生产' | '熔断';
}

/** 转介/协同（PRD 6.4 Referral） */
export interface Referral {
  id: string;
  clueId: string;
  studentName: string;
  from: string; // 发起人
  target: '心理' | '资助' | '教务' | '保卫' | '学院' | '家长';
  status: '待接收' | '处理中' | '已回填' | '已关闭';
  reason: string;
  result?: string;
  createdAt: string;
  steps: { time: string; actor: string; action: string }[];
}

/** 审计日志（PRD 6.5 AuditLog） */
export interface AuditLog {
  id: string;
  operator: string;
  role: string;
  action:
    | '查看'
    | '导出'
    | '跟进'
    | '规则修改'
    | '白名单调整'
    | '登录'
    | '下钻'
    | '越权拦截'
    | '尝试访问';
  target: string;
  time: string;
  ip: string;
  traceId: string;
  result: '成功' | '拒绝';
}

/** 数据质量（PRD 6.5 DataQuality） */
export interface DataQuality {
  source: string;
  owner: string; // 数据责任人/部门
  freq: string; // 更新频率
  pkIntegrity: number; // 主键完整性 %
  continuity: number; // 连续性 %
  coverage: number; // 覆盖率 %
  lastUpdate: string;
  fused: boolean; // 是否熔断
}

/** 模型版本（PRD 6.5 ModelVersion） */
export interface ModelVersion {
  version: string;
  mirror: string; // 所属镜（观变/合证…）
  precisionAtK: number;
  recallAtK: number;
  noiseRate: number;
  fairness: string; // 分群公平性结论
  complianceCheck: '通过' | '待复核' | '未通过';
  releaseStatus: '灰度' | '生产' | '回退';
  rollbackBaseline: string;
}

/** 报表（PRD 6.5 Report） */
export interface CollegeStat {
  college: string;
  newClues: number;
  closeRate: number; // 闭环率 %
  noiseRate: number; // 噪声率 %
  overdue: number;
}

/** 合规检查关卡（PRD 5.6 / M16） */
export interface ComplianceGate {
  step: string;
  desc: string;
  status: '已完成' | '进行中' | '待开始';
  owner: string;
  updatedAt: string;
}

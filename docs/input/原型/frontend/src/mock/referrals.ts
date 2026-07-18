import type { Referral } from '@/types';

export const referrals: Referral[] = [
  {
    id: 'RF-2026-0012',
    clueId: 'C-2026-0001',
    studentName: '张××',
    from: '王老师（辅导员）',
    target: '心理',
    status: '处理中',
    reason: '毕业季压力较大，授权范围内联动心理中心评估。',
    createdAt: '2026-06-24 09:10',
    steps: [
      { time: '2026-06-24 09:10', actor: '王老师', action: '发起转介至心理中心' },
      { time: '2026-06-24 10:02', actor: '心理中心·林老师', action: '已接收，安排预约' },
    ],
  },
  {
    id: 'RF-2026-0013',
    clueId: 'C-2026-0002',
    studentName: '李××',
    from: '王老师（辅导员）',
    target: '资助',
    status: '已回填',
    reason: '疑似隐性经济压力，转介资助中心了解政策可及性（不发放资金）。',
    result: '已纳入临时困难补助评估，本周完成访谈。',
    createdAt: '2026-06-23 14:20',
    steps: [
      { time: '2026-06-23 14:20', actor: '王老师', action: '发起转介至资助中心' },
      { time: '2026-06-23 16:40', actor: '资助中心·黄老师', action: '已接收' },
      { time: '2026-06-24 09:00', actor: '资助中心·黄老师', action: '回填处理结果' },
    ],
  },
  {
    id: 'RF-2026-0014',
    clueId: 'C-2026-0003',
    studentName: '赵××',
    from: '王老师（辅导员）',
    target: '保卫',
    status: '待接收',
    reason: '连续无门禁记录，请协助核实在校安全情况。',
    createdAt: '2026-06-24 08:30',
    steps: [{ time: '2026-06-24 08:30', actor: '王老师', action: '发起转介至保卫处' }],
  },
];

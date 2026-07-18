import type { CollegeStat } from '@/types';

/** 各学院对比（驾驶舱/周报月报） */
export const collegeStats: CollegeStat[] = [
  { college: '计算机科学与技术学院', newClues: 23, closeRate: 86.5, noiseRate: 18.2, overdue: 2 },
  { college: '外国语学院', newClues: 11, closeRate: 90.1, noiseRate: 15.0, overdue: 1 },
  { college: '数学科学学院', newClues: 14, closeRate: 82.3, noiseRate: 21.4, overdue: 3 },
  { college: '物理科学与技术学院', newClues: 9, closeRate: 88.0, noiseRate: 16.7, overdue: 0 },
  { college: '医学部', newClues: 18, closeRate: 79.5, noiseRate: 23.1, overdue: 4 },
  { college: '商学院', newClues: 16, closeRate: 84.6, noiseRate: 19.0, overdue: 2 },
];

/** 驾驶舱核心指标 */
export const dashboardKpi = {
  newCluesToday: 91,
  keyStudents: 47,
  closeRate: 85.3,
  noiseRate: 18.9,
  pendingTotal: 38,
  overdueTotal: 12,
};

/** 近 7 日趋势 */
export const trend7d = {
  dates: ['06-18', '06-19', '06-20', '06-21', '06-22', '06-23', '06-24'],
  newClues: [62, 70, 58, 81, 75, 88, 91],
  closed: [55, 60, 52, 70, 66, 79, 80],
};

/** 线索类别分布 */
export const categoryDist = [
  { name: '学业关怀', value: 41 },
  { name: '经济关怀', value: 28 },
  { name: '安全关怀', value: 22 },
];

/** 关怀等级分布 */
export const levelDist = [
  { name: 'Ⅰ级 提醒', value: 52 },
  { name: 'Ⅱ级 重点', value: 27 },
  { name: 'Ⅲ级 重点关怀', value: 12 },
];

/** 噪声原因分布（周报月报） */
export const noiseReasons = [
  { name: '临近假期正常波动', value: 32 },
  { name: '校外就餐/借卡', value: 24 },
  { name: '设备/数据异常', value: 18 },
  { name: '已请假未同步', value: 14 },
  { name: '其他', value: 12 },
];

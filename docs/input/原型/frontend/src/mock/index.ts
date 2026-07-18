// Mock 数据统一出口
export { students, findStudent } from './students';
export { clues, findClue } from './clues';
export { rules } from './rules';
export { referrals } from './referrals';
export { auditLogs } from './audit';
export { dataQuality } from './dataQuality';
export { modelVersions, modelGates, complianceGates } from './models';
export {
  collegeStats,
  dashboardKpi,
  trend7d,
  categoryDist,
  levelDist,
  noiseReasons,
} from './reports';
export { demoUsers } from './users';

import { clues } from './clues';
import { students } from './students';
import type { IdentityTag } from '@/types';

/** 辅导员工作台统计（演示） */
export const workbenchStats = {
  pending: clues.filter((c) => c.status === 'pending').length,
  processing: clues.filter((c) => c.status === 'processing').length,
  followedToday: clues.filter((c) => c.status === 'followed').length,
  overdue: clues.filter((c) => c.status === 'overdue').length,
  todayCapacity: 15,
  todayDone: 6,
};

/** 身份标签聚合（三级：普通/一般关注/重点关注） */
export const identitySummary = (['普通', '一般关注', '重点关注'] as IdentityTag[]).map((tag) => ({
  tag,
  count: students.filter((s) => s.identityTag === tag).length,
  students: students.filter((s) => s.identityTag === tag).map((s) => s.name),
}));

/** 关爱标签聚合（学生标签体系页用） */
export const tagSummary = (() => {
  const map = new Map<
    string,
    { type: string; sensitive: boolean; count: number; students: string[] }
  >();
  students.forEach((s) => {
    s.tags.forEach((t) => {
      const entry = map.get(t.type) ?? {
        type: t.type,
        sensitive: t.sensitive,
        count: 0,
        students: [],
      };
      entry.count += 1;
      entry.students.push(s.name);
      map.set(t.type, entry);
    });
  });
  return Array.from(map.values());
})();

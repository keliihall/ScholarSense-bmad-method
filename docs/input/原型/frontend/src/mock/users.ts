import type { CurrentUser, RoleKey } from '@/types';

/** 各角色的演示用户（角色切换用） */
export const demoUsers: Record<RoleKey, CurrentUser> = {
  counselor: {
    id: 'T001',
    name: '王老师',
    role: 'counselor',
    college: '计算机科学与技术学院',
    studentCount: 186,
    avatarText: '王',
  },
  college: {
    id: 'T100',
    name: '李主任',
    role: 'college',
    college: '计算机科学与技术学院',
    avatarText: '李',
  },
  affairs: { id: 'T200', name: '学工管理员', role: 'affairs', avatarText: '工' },
  leader: { id: 'T300', name: '赵处长', role: 'leader', avatarText: '赵' },
  collaborator: {
    id: 'T400',
    name: '林老师',
    role: 'collaborator',
    college: '心理健康中心',
    avatarText: '林',
  },
  ops: { id: 'T500', name: '系统管理员', role: 'ops', avatarText: '运' },
  data: { id: 'T600', name: '数据责任人', role: 'data', college: '信息中心', avatarText: '数' },
};

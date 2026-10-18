import type { PostVO } from '@/api/system/post/types';
import type { RoleVO } from '@/api/system/role/types';

/**
 * 用户信息
 */
export interface UserInfo {
  user: UserVO;
  roles: string[];
  permissions: string[];
}

/**
 * 用户查询对象类型
 */
export interface UserQuery extends PageQuery {
  userName?: string;
  nickName?: string;
  phoneNumber?: string;
  status?: string;
  deptId?: string | number;
  roleId?: string | number;
  userIds?: string | number | (string | number)[] | undefined;
}

/**
 * 用户返回对象
 */
export interface UserVO extends BaseEntity {
  userId: string | number;
  tenantId: string;
  deptId: number;
  userName: string;
  nickName: string;
  userType: string;
  email: string;
  phoneNumber: string;
  gender: string;
  avatar?: string | number;
  avatarUrl?: string;
  status: string;
  delFlag: string;
  loginIp: string;
  loginDate: string;
  remark: string;
  deptName: string;
  /** 详情接口可能返回嵌套部门 */
  dept?: { deptName?: string };
  roles: RoleVO[];
  roleIds: any;
  postIds: any;
  roleId: any;
  admin: boolean;
}

/**
 * 用户表单类型
 */
export interface UserForm {
  id?: string;
  userId?: string;
  deptId?: number;
  userName: string;
  nickName?: string;
  password: string;
  phoneNumber?: string;
  email?: string;
  gender?: string;
  status: string;
  remark?: string;
  avatar?: string | number;
  postIds: string[];
  roleIds: string[];
}

/**
 * 个人资料表单类型
 */
export interface UserProfileForm {
  nickName?: string;
  phoneNumber?: string;
  email?: string;
  gender?: string;
  avatar?: string | number;
}

export interface UserInfoVO {
  user: UserVO;
  roles: RoleVO[];
  roleIds: string[];
  posts: PostVO[];
  postIds: string[];
  roleGroup: string;
  postGroup: string;
}

export interface ResetPwdForm {
  oldPassword: string;
  newPassword: string;
  confirmPassword: string;
}

/**
 * 员工同步提示类型
 */
export type UserSyncWarningType = 'SKIPPED' | 'FIELD_CLEARED' | 'NOT_RETURNED' | 'BOUND' | 'CACHE_REFRESH_FAILED';

/**
 * 员工同步单条提示（不含手机号、邮箱等联系方式）
 */
export interface UserSyncWarningVO {
  type: UserSyncWarningType;
  /** 外部人员 ID */
  hrmUserId?: string;
  /** 人员姓名或本地昵称 */
  name?: string;
  /** 中文说明 */
  reason: string;
}

/**
 * 按部门同步员工结果
 */
export interface UserSyncResultVO {
  deptId: string | number;
  sourceDepartmentId: string;
  fetched: number;
  added: number;
  updated: number;
  disabled: number;
  skipped: number;
  warnings: UserSyncWarningVO[];
}

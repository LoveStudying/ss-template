/**
 * 部门查询参数
 */
export interface DeptQuery extends PageQuery {
  deptName?: string;
  deptCategory?: string;
  status?: number;
  deptType?: DeptType;
  businessId?: string;
}

/**
 * 部门类型
 */
export interface DeptVO extends BaseEntity {
  /** 部门类型，未绑定组织来源时为空 */
  deptType?: DeptType | null;
  /** 组织来源业务 ID */
  businessId?: string | null;
  /** 来源快照：所属分部 ID */
  hrmSubcompanyId?: string | null;
  /** 来源快照：上级部门 ID */
  hrmSupdepartmentId?: string | null;
  /** 来源快照：上级分部 ID */
  hrmSupsubcompanyId?: string | null;
  /** 来源快照：编码 */
  hrmCode?: string | null;
  /** 来源快照：全称 */
  hrmFullName?: string | null;
  /** 来源快照：停用标记，"1" 停用，"0" 或空串正常 */
  hrmCanceled?: string | null;
  /** 来源快照：上游变更时间 */
  hrmLastChangeTime?: string | null;
  /** 最近同步时间 */
  lastSyncTime?: string | null;
  id: number | string;
  parentName: string;
  parentId: number | string;
  children: DeptVO[];
  deptId: number | string;
  deptName: string;
  deptCategory: string;
  orderNum: number;
  leader: string;
  phone: string;
  email: string;
  status: string;
  delFlag: string;
  ancestors: string;
  menuId: string | number;
}

/**
 * 部门类型
 */
export interface DeptTreeVO extends BaseEntity {
  id: number | string;
  label: string;
  parentId: number | string;
  weight: number;
  children: DeptTreeVO[];
  disabled: boolean;
}

/**
 * 部门表单类型
 */
export interface DeptForm {
  deptType?: DeptType | '';
  businessId?: string;
  parentName?: string;
  parentId?: number | string;
  children?: DeptForm[];
  deptId?: number | string;
  deptName?: string;
  deptCategory?: string;
  orderNum?: number;
  leader?: string;
  phone?: string;
  email?: string;
  status?: string;
  delFlag?: string;
  ancestors?: string;
}

/** 部门类型：分部 / 普通部门 */
export type DeptType = 'subcompany' | 'department';

/** 部门类型选项，与后端常量保持一致 */
export const DEPT_TYPE_OPTIONS: ReadonlyArray<{ label: string; value: DeptType }> = [
  { label: '分部', value: 'subcompany' },
  { label: '普通部门', value: 'department' }
];

/** 部门同步警告摘要 */
export interface DeptSyncWarning {
  businessId: string;
  reason: 'HAS_USERS' | 'ACTIVE_DESCENDANT' | 'CACHE_REFRESH_FAILED';
  localStatus: string | null;
  hrmCanceled: string | null;
}

/** 部门子树同步结果 */
export interface DeptSyncResult {
  deptId: string | number;
  subcompanyId: string;
  syncTime: string;
  fetchedCount: number;
  /** 排除停用节点及其子树后，实际参与同步的后代数量 */
  selectedCount: number;
  createdCount: number;
  updatedCount: number;
  unchangedCount: number;
  statusSkippedCount: number;
  warnings: DeptSyncWarning[];
  warningCount: number;
  warningsTruncated: boolean;
}

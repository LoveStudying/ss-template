package org.dromara.system.domain.sync;

import java.time.LocalDateTime;

/**
 * 外部组织来源中的普通部门，字段与具体来源格式无关。
 *
 * @param id             部门业务 ID，正整数串
 * @param subcompanyId   所属分部业务 ID
 * @param parentId       上级部门业务 ID，分部下的顶级部门为 "0"
 * @param name           部门名称，不为空且不超过 30 字
 * @param fullName       部门全称，不超过 200 字
 * @param code           部门编码，不超过 64 字
 * @param orderNum       同级排序号，不为 null
 * @param disabled       来源中是否已封存/停用
 * @param rawStatus      来源原始状态值，原样保留供落库
 * @param lastChangeTime 来源最后变更时间，未知为 null
 */
public record OrgDepartment(String id, String subcompanyId, String parentId, String name, String fullName,
                            String code, Integer orderNum, boolean disabled, String rawStatus,
                            LocalDateTime lastChangeTime) {
}

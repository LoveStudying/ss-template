package org.dromara.system.domain.sync;

import java.time.LocalDate;

/**
 * 外部组织来源中的员工，文本字段已去除首尾空白，空白值为 null。
 *
 * @param id             员工业务 ID，可能为 null，由同步层跳过
 * @param workcode       工号
 * @param loginId        登录名
 * @param name           姓名
 * @param gender         本地性别字典值：0 男、1 女、2 未知
 * @param email          邮箱
 * @param mobile         手机号
 * @param active         是否在职
 * @param rawStatus      来源原始状态值，原样保留供落库
 * @param departmentId   所属部门业务 ID
 * @param subcompanyId   所属分部业务 ID
 * @param lastChangeDate 来源最后变更日期，未知或非法为 null
 */
public record OrgEmployee(String id, String workcode, String loginId, String name, String gender,
                          String email, String mobile, boolean active, String rawStatus,
                          String departmentId, String subcompanyId, LocalDate lastChangeDate) {
}

package org.dromara.system.domain.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.common.sensitive.annotation.Sensitive;
import org.dromara.common.sensitive.core.SensitiveStrategy;
import org.dromara.common.translation.annotation.Translation;
import org.dromara.common.translation.constant.TransConstant;
import org.dromara.system.domain.SysUser;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;


/**
 * 用户信息视图对象 sys_user
 *
 * @author Michelle.Chung
 */
@Data
@AutoMapper(target = SysUser.class)
public class SysUserVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 部门ID
     */
    private Long deptId;

    /**
     * 用户账号
     */
    private String userName;

    /**
     * 用户昵称
     */
    private String nickName;

    /**
     * 用户类型（sys_user系统用户）
     */
    private String userType;

    /**
     * 用户邮箱
     */
    @Sensitive(strategy = SensitiveStrategy.EMAIL, perms = "system:user:edit")
    private String email;

    /**
     * 手机号码
     */
    @Sensitive(strategy = SensitiveStrategy.PHONE, perms = "system:user:edit")
    private String phoneNumber;

    /**
     * 用户性别（0男 1女 2未知）
     */
    private String gender;

    /**
     * 头像 OSS ID
     */
    private Long avatar;

    /**
     * 头像地址
     */
    @Translation(type = TransConstant.OSS_ID_TO_URL, mapper = "avatar")
    private String avatarUrl;

    /**
     * 密码
     */
    @JsonIgnore
    @JsonProperty
    private String password;

    /**
     * 账号状态（0正常 1停用）
     */
    private String status;

    /**
     * 最后登录IP
     */
    private String loginIp;

    /**
     * 最后登录时间
     */
    private LocalDateTime loginDate;

    /**
     * 备注
     */
    private String remark;

    /**
     * 外部组织来源的人员主键，历史命名 hrm_user_id；员工同步的匹配键。
     */
    private String hrmUserId;

    /**
     * 外部组织来源的人员编码，历史命名 hrm_workcode；不作为身份键。
     */
    private String hrmWorkcode;

    /**
     * 外部组织来源的原始人员状态，历史命名 hrm_status；是否在职由来源适配器解释。
     */
    private String hrmStatus;

    /**
     * 外部组织来源的原始部门 ID，历史命名 hrm_department_id。
     */
    private String hrmDepartmentId;

    /**
     * 外部组织来源的原始分部 ID，历史命名 hrm_subcompany_id。
     */
    private String hrmSubcompanyId;

    /**
     * 外部组织来源记录的最后变更日期，历史命名 hrm_last_change_date。
     */
    private LocalDate hrmLastChangeDate;

    /**
     * 该用户最近一次被员工同步写入的时间，历史命名 hrm_sync_time。
     */
    private LocalDateTime hrmSyncTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 部门名
     */
    @Translation(type = TransConstant.DEPT_ID_TO_NAME, mapper = "deptId")
    private String deptName;

    /**
     * 角色对象
     */
    private List<SysRoleVo> roles;

    /**
     * 角色组
     */
    private Long[] roleIds;

    /**
     * 岗位组
     */
    private Long[] postIds;

    /**
     * 数据权限 当前角色ID
     */
    private Long roleId;

}

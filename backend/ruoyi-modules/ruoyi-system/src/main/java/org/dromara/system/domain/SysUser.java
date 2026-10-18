package org.dromara.system.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户对象 sys_user
 *
 * @author Lion Li
 */

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    /**
     * 用户ID
     */
    @TableId(value = "user_id")
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
    private String email;

    /**
     * 手机号码
     */
    private String phoneNumber;

    /**
     * 用户性别
     */
    private String gender;

    /**
     * 用户头像
     */
    private Long avatar;

    /**
     * 密码
     */
    @TableField(
        insertStrategy = FieldStrategy.NOT_EMPTY,
        updateStrategy = FieldStrategy.NOT_EMPTY,
        whereStrategy = FieldStrategy.NOT_EMPTY
    )
    private String password;

    /**
     * 账号状态（0正常 1停用）
     */
    private String status;

    /**
     * 删除标志（0代表存在 1代表删除）
     */
    @TableLogic
    private String delFlag;

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
     * 外部组织来源的原始人员状态，历史命名 hrm_status；保留原值，是否在职由来源适配器解释。
     */
    private String hrmStatus;

    /**
     * 外部组织来源的原始部门 ID，历史命名 hrm_department_id；用于判断员工归属是否变化。
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
     * 使用用户ID构造系统用户对象。
     *
     * @param userId 用户ID
     */
    public SysUser(Long userId) {
        this.userId = userId;
    }

    /**
     * 判断当前用户是否为超级管理员。
     *
     * @return true 是超级管理员 false 不是超级管理员
     */
    public boolean isSuperAdmin() {
        return SystemConstants.SUPER_ADMIN_USER_ID.equals(this.userId);
    }

}

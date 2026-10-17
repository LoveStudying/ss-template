package org.dromara.system.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 部门表 sys_dept
 *
 * @author Lion Li
 */

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dept")
public class SysDept extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 部门ID
     */
    @TableId(value = "dept_id")
    private Long deptId;

    /**
     * 父部门ID
     */
    private Long parentId;

    /**
     * 部门名称
     */
    private String deptName;

    /**
     * 部门类别编码
     */
    private String deptCategory;

    /**
     * 显示顺序
     */
    private Integer orderNum;

    /**
     * 负责人
     */
    private Long leader;

    /**
     * 联系电话
     */
    private String phone;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 部门状态:0正常,1停用
     */
    private String status;

    /**
     * 删除标志（0代表存在 1代表删除）
     */
    @TableLogic
    private String delFlag;

    /**
     * 祖级列表
     */
    private String ancestors;

    /**
     * 子部门
     */
    @TableField(exist = false)
    private List<SysDept> children = new ArrayList<>();

    /**
     * 外部组织来源的部门类型：分部或普通部门，为空表示人工创建、未绑定来源。
     */
    private String deptType;

    /**
     * 外部组织来源业务身份，保留来源原值，与部门类型共同构成来源唯一标识。
     */
    private String businessId;

    /**
     * 外部组织来源所属分部 ID，历史命名 hrm_subcompany_id。
     */
    private String hrmSubcompanyId;

    /**
     * 外部组织来源的上级部门 ID，历史命名 hrm_supdepartment_id；仅作来源侧对照，不参与本地部门树。
     */
    private String hrmSupdepartmentId;

    /**
     * 外部组织来源的上级分部 ID，历史命名 hrm_supsubcompany_id。
     */
    private String hrmSupsubcompanyId;

    /**
     * 外部组织来源部门编码，历史命名 hrm_code；不作为身份键。
     */
    private String hrmCode;

    /**
     * 外部组织来源部门全称，历史命名 hrm_full_name；与本地部门名称可能不同。
     */
    private String hrmFullName;

    /**
     * 外部组织来源原始停用状态，历史命名 hrm_canceled；保留来源原值，含义由来源适配器解释。
     */
    private String hrmCanceled;

    /**
     * 外部组织来源记录的变更时间，历史命名 hrm_last_change_time；按来源业务时区保存。
     */
    private LocalDateTime hrmLastChangeTime;

    /**
     * 该部门最近一次成功同步的时间，同步失败不更新。
     */
    private LocalDateTime lastSyncTime;

}

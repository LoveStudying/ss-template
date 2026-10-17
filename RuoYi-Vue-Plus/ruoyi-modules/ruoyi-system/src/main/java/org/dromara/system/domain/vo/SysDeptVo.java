package org.dromara.system.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelIgnoreUnannotated;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.dromara.common.excel.annotation.ExcelDictFormat;
import org.dromara.common.excel.convert.ExcelDictConvert;
import org.dromara.system.domain.SysDept;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 部门视图对象 sys_dept
 *
 * @author Michelle.Chung
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = SysDept.class)
public class SysDeptVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 部门id
     */
    @ExcelProperty(value = "部门id")
    private Long deptId;

    /**
     * 父部门id
     */
    private Long parentId;

    /**
     * 父部门名称
     */
    private String parentName;

    /**
     * 祖级列表
     */
    private String ancestors;

    /**
     * 部门名称
     */
    @ExcelProperty(value = "部门名称")
    private String deptName;

    /**
     * 部门类别编码
     */
    @ExcelProperty(value = "部门类别编码")
    private String deptCategory;

    /**
     * 显示顺序
     */
    private Integer orderNum;

    /**
     * 负责人ID
     */
    private Long leader;

    /**
     * 负责人
     */
    @ExcelProperty(value = "负责人")
    private String leaderName;

    /**
     * 联系电话
     */
    @ExcelProperty(value = "联系电话")
    private String phone;

    /**
     * 邮箱
     */
    @ExcelProperty(value = "邮箱")
    private String email;

    /**
     * 部门状态（0正常 1停用）
     */
    @ExcelProperty(value = "部门状态", converter = ExcelDictConvert.class)
    @ExcelDictFormat(dictType = "sys_normal_disable")
    private String status;

    /**
     * 创建时间
     */
    @ExcelProperty(value = "创建时间")
    private LocalDateTime createTime;

    /**
     * 子部门
     */
    private List<SysDeptVo> children = new ArrayList<>();

    /**
     * 外部组织来源的部门类型：分部或普通部门，为空表示未绑定来源。
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
     * 外部组织来源的上级部门 ID，历史命名 hrm_supdepartment_id。
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
     * 外部组织来源部门全称，历史命名 hrm_full_name。
     */
    private String hrmFullName;

    /**
     * 外部组织来源原始停用状态，历史命名 hrm_canceled；保留来源原值。
     */
    private String hrmCanceled;

    /**
     * 外部组织来源记录的变更时间，历史命名 hrm_last_change_time。
     */
    private LocalDateTime hrmLastChangeTime;

    /**
     * 该部门最近一次成功同步的时间。
     */
    private LocalDateTime lastSyncTime;

}

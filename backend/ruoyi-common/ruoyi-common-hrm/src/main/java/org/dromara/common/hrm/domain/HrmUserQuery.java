package org.dromara.common.hrm.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** 人员查询条件；空条件发送空字符串，筛选语义由 OA 决定。 */
@Getter
@Setter
public class HrmUserQuery {

    /** 人员编码，对应 in1。 */
    private String workcode;

    /** 分部 ID，多个用英文逗号分隔，对应 in2。 */
    private String subcompanyId;

    /** 部门 ID，多个用英文逗号分隔，对应 in3。 */
    private String departmentId;

    /** 岗位 ID，多个用英文逗号分隔，对应 in4。 */
    private String jobtitleId;

    /** 最后修改日期，按 yyyy-MM-dd 发送为 in5，边界含义待 OA 确认。 */
    private LocalDate lastChangeDate;
}

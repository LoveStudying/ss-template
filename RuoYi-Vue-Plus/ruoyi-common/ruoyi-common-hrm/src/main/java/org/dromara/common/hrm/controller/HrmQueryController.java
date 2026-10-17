package org.dromara.common.hrm.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.common.hrm.client.HrmClientException;
import org.dromara.common.hrm.domain.HrmUserQuery;
import org.dromara.common.hrm.domain.vo.HrmQueryVo;
import org.dromara.common.hrm.service.HrmQueryService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 泛微人力资源只读查询入口。 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/hrm")
public class HrmQueryController extends BaseController {

    private final HrmQueryService queryService;

    /**
     * 查询分部。
     *
     * @return 客户端获取到的全部分部字段，不含密码
     */
    @SaCheckPermission("hrm:subcompany:query")
    @GetMapping("/subcompanies")
    public R<HrmQueryVo> subcompanies() {
        return R.ok(queryService.subcompanies());
    }

    /**
     * 查询部门。
     *
     * @param subcompanyId 分部 ID，多个用英文逗号分隔
     * @return 客户端获取到的全部部门字段，不含密码
     */
    @SaCheckPermission("hrm:department:query")
    @GetMapping("/departments")
    public R<HrmQueryVo> departments(@RequestParam(value = "subcompanyId", required = false) String subcompanyId) {
        return R.ok(queryService.departments(subcompanyId));
    }

    /**
     * 查询岗位。
     *
     * @param subcompanyId 分部 ID，多个用英文逗号分隔
     * @param departmentId 部门 ID，多个用英文逗号分隔
     * @return 客户端获取到的全部岗位字段，不含密码
     */
    @SaCheckPermission("hrm:jobtitle:query")
    @GetMapping("/job-titles")
    public R<HrmQueryVo> jobTitles(@RequestParam(value = "subcompanyId", required = false) String subcompanyId,
                                   @RequestParam(value = "departmentId", required = false) String departmentId) {
        return R.ok(queryService.jobTitles(subcompanyId, departmentId));
    }

    /**
     * 查询指定员工或组织范围内的人员。
     *
     * @param query 人员筛选条件，必须提供编码或组织范围
     * @return 客户端获取到的全部人员字段，不含密码
     */
    @SaCheckPermission("hrm:user:query")
    @GetMapping("/users")
    public R<HrmQueryVo> users(HrmUserQuery query) {
        return R.ok(queryService.users(query));
    }

    /**
     * 将客户端已经脱敏的错误说明返回给调用方。
     *
     * @param exception 客户端调用异常
     * @return 不包含 OA 响应正文的失败结果
     */
    @ExceptionHandler(HrmClientException.class)
    public R<Void> handleHrmClientException(HrmClientException exception) {
        return R.fail(exception.getMessage());
    }
}

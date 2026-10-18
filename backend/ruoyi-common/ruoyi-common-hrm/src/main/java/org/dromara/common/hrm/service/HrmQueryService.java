package org.dromara.common.hrm.service;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.hrm.client.HrmClient;
import org.dromara.common.hrm.domain.HrmUserQuery;
import org.dromara.common.hrm.domain.vo.HrmQueryVo;

/** 泛微人力资源只读接口查询服务。 */
@RequiredArgsConstructor
public class HrmQueryService {

    private final HrmClient hrmClient;

    /**
     * 查询客户端获取到的全部分部字段。
     *
     * @return 全部分部字段结果，不含客户端已丢弃的密码
     */
    public HrmQueryVo subcompanies() {
        return HrmQueryVo.from(hrmClient.getHrmSubcompanyInfo());
    }

    /**
     * 查询客户端获取到的全部部门字段。
     *
     * @param subcompanyId 分部 ID，多个用英文逗号分隔
     * @return 全部部门字段结果，不含客户端已丢弃的密码
     */
    public HrmQueryVo departments(String subcompanyId) {
        return HrmQueryVo.from(hrmClient.getHrmDepartmentInfo(subcompanyId));
    }

    /**
     * 查询客户端获取到的全部岗位字段。
     *
     * @param subcompanyId 分部 ID，多个用英文逗号分隔
     * @param departmentId 部门 ID，多个用英文逗号分隔
     * @return 全部岗位字段结果，不含客户端已丢弃的密码
     */
    public HrmQueryVo jobTitles(String subcompanyId, String departmentId) {
        return HrmQueryVo.from(hrmClient.getHrmJobTitleInfo(subcompanyId, departmentId));
    }

    /**
     * 按人员编码或组织范围查询客户端获取到的全部人员字段。
     *
     * @param query 查询条件，必须提供编码或组织筛选
     * @return 全部人员字段结果，不含客户端已丢弃的密码
     */
    public HrmQueryVo users(HrmUserQuery query) {
        if (blank(query.getWorkcode()) && blank(query.getSubcompanyId())
            && blank(query.getDepartmentId()) && blank(query.getJobtitleId())) {
            throw new ServiceException("人员查询必须提供工号、分部、部门或岗位筛选条件");
        }
        return HrmQueryVo.from(hrmClient.getHrmUserInfo(query));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

package org.dromara.system.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.hrm.client.HrmClient;
import org.dromara.common.hrm.client.HrmClientException;
import org.dromara.common.hrm.domain.HrmQueryResult;
import org.dromara.common.hrm.domain.HrmRecord;
import org.dromara.common.hrm.domain.HrmUserQuery;
import org.dromara.system.config.OrgSyncProperties;
import org.dromara.system.domain.sync.OrgDepartment;
import org.dromara.system.domain.sync.OrgDepartmentSnapshot;
import org.dromara.system.domain.sync.OrgEmployee;
import org.dromara.system.domain.sync.OrgSubcompany;
import org.dromara.system.service.OrgSyncSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 泛微 OA 人力资源组织来源，负责 HRM 字段名、状态取值与日期格式到中性模型的映射。
 * <p>
 * HRM 格式细节只允许出现在本类中，同步与绑定逻辑只依赖 {@link OrgSyncSource}。
 */
@Component
@ConditionalOnProperty(prefix = "org-sync", name = "source", havingValue = "hrm", matchIfMissing = true)
public class HrmOrgSyncSource implements OrgSyncSource {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT);

    /** HRM 封存标记允许的取值：空串、0 未封存、1 已封存。 */
    private static final Set<String> CANCELED_VALUES = Set.of("", "0", "1");

    /** HRM 封存标记中表示已封存的值。 */
    private static final String CANCELED = "1";

    /** HRM 人员状态中表示在职（正式）的值。 */
    private static final String STATUS_ACTIVE = "1";

    private final HrmClient client;
    private final OrgSnapshotValidator validator;

    /**
     * 创建 HRM 来源。
     *
     * @param client    HRM 只读客户端
     * @param validator 部门快照校验器
     */
    public HrmOrgSyncSource(HrmClient client, OrgSnapshotValidator validator) {
        this.client = client;
        this.validator = validator;
    }

    /**
     * 来源标识。
     *
     * @return {@code hrm}
     */
    @Override
    public String source() {
        return OrgSyncProperties.HRM;
    }

    /**
     * 查询全部分部，校验 ID、封存状态、字段长度与变更时间。
     *
     * @return 分部列表
     */
    @Override
    public List<OrgSubcompany> listSubcompanies() {
        HrmQueryResult result = call(client::getHrmSubcompanyInfo);
        requireResult(result);
        List<OrgSubcompany> list = new ArrayList<>();
        for (HrmRecord record : result.records()) {
            Map<String, String> f = fields(record);
            String id = f.get("_subcompanyid");
            OrgSnapshotValidator.validateId(id, false);
            String parent = f.get("_supsubcompanyid");
            OrgSnapshotValidator.validateId(parent, true);
            String canceled = canceled(f, id);
            String fullName = limited(f.get("_fullname"), 200, id);
            String code = limited(f.get("_code"), 64, id);
            LocalDateTime time;
            try {
                time = time(f.get("lastChangdate"));
            } catch (DateTimeException e) {
                throw error(id, "日期非法");
            }
            list.add(new OrgSubcompany(id, parent, fullName, code, CANCELED.equals(canceled), canceled, time));
        }
        return list;
    }

    /**
     * 查询分部完整部门快照；null 结果、空记录、未知封存状态、非法排序或日期均抛异常。
     *
     * @param subcompanyId 分部业务 ID
     * @return 已校验并按父先子后排序的快照
     */
    @Override
    public OrgDepartmentSnapshot listDepartments(String subcompanyId) {
        OrgSnapshotValidator.validateId(subcompanyId, false);
        HrmQueryResult result = call(() -> client.getHrmDepartmentInfo(subcompanyId));
        requireResult(result);
        List<OrgDepartment> departments = new ArrayList<>();
        for (HrmRecord record : result.records()) {
            Map<String, String> f = fields(record);
            String id = f.get("_departmentid");
            String canceled = canceled(f, id);
            String fullName = f.get("_fullname");
            String name = f.get("_shortname");
            if (name == null || name.isBlank()) {
                name = fullName;
            }
            if (name == null || name.isBlank()) {
                throw error(id, "名称为空");
            }
            Integer order;
            LocalDateTime time;
            try {
                String rawOrder = f.get("_showorder");
                if (rawOrder == null) {
                    throw new IllegalArgumentException();
                }
                order = rawOrder.isEmpty() ? 0 : Integer.valueOf(rawOrder);
                time = time(f.get("lastChangdate"));
            } catch (IllegalArgumentException | DateTimeException e) {
                throw error(id, "排序或日期非法");
            }
            departments.add(new OrgDepartment(id, f.get("_subcompanyid"), f.get("_supdepartmentid"), name,
                fullName, f.get("_code"), order, CANCELED.equals(canceled), canceled, time));
        }
        return validator.validate(subcompanyId, departments);
    }

    /**
     * 查询部门员工；null 结果返回空列表，null 元素丢弃，不跳过空 ID 或重复 ID。
     *
     * @param departmentId 部门业务 ID
     * @return 员工列表
     */
    @Override
    public List<OrgEmployee> listEmployees(String departmentId) {
        HrmUserQuery query = new HrmUserQuery();
        query.setDepartmentId(departmentId);
        HrmQueryResult result = call(() -> client.getHrmUserInfo(query));
        List<OrgEmployee> employees = new ArrayList<>();
        if (result == null || result.nullResult()) {
            return employees;
        }
        for (HrmRecord record : result.records()) {
            if (record == null) {
                continue;
            }
            Map<String, String> f = record.fields();
            String status = text(f.get("status"));
            employees.add(new OrgEmployee(text(f.get("userid")), text(f.get("workcode")), text(f.get("loginid")),
                text(f.get("lastname")), gender(text(f.get("sex"))), text(f.get("email")), text(f.get("mobile")),
                isActiveStatus(status), status, text(f.get("departmentid")), text(f.get("subcompanyid1")),
                date(f.get("lastChangdate"))));
        }
        return employees;
    }

    /**
     * HRM 人员状态为 1 时视为在职。
     *
     * @param rawStatus 原始状态
     * @return 是否在职
     */
    @Override
    public boolean isActiveStatus(String rawStatus) {
        return STATUS_ACTIVE.equals(rawStatus);
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (HrmClientException e) {
            throw new ServiceException(e.getMessage());
        }
    }

    private static void requireResult(HrmQueryResult result) {
        if (result == null || result.nullResult()) {
            throw error(null, "查询结果为空");
        }
    }

    private static Map<String, String> fields(HrmRecord record) {
        if (record == null) {
            throw error(null, "空记录");
        }
        return record.fields();
    }

    private static String canceled(Map<String, String> f, String id) {
        String canceled = f.get("_canceled");
        if (canceled == null || !CANCELED_VALUES.contains(canceled)) {
            throw error(id, "状态缺失或未知");
        }
        return canceled;
    }

    private static LocalDateTime time(String raw) {
        return raw == null || raw.isEmpty() ? null : LocalDateTime.parse(raw, TIME);
    }

    private static String gender(String sex) {
        if ("男".equals(sex)) {
            return "0";
        }
        if ("女".equals(sex)) {
            return "1";
        }
        return "2";
    }

    private static String text(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private static LocalDate date(String value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String limited(String value, int max, String id) {
        if (value != null && value.length() > max) {
            throw error(id, "字段超长");
        }
        return value;
    }

    private static ServiceException error(String id, String reason) {
        String safe = id == null ? "?" : id.replaceAll("[^0-9]", "?");
        return new ServiceException("HRM 节点 " + safe.substring(0, Math.min(64, safe.length())) + "：" + reason);
    }
}

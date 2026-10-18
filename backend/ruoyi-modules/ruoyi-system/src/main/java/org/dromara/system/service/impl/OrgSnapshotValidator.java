package org.dromara.system.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.sync.OrgDepartment;
import org.dromara.system.domain.sync.OrgDepartmentSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 与来源格式无关的部门快照校验，不进行网络或数据库访问。
 */
@Component
public class OrgSnapshotValidator {

    /**
     * 校验业务 ID：1~64 位数字串且不全为零，不去空白、不去前导零；allowZero 时额外接受 "0"。
     *
     * @param value     业务 ID
     * @param allowZero 是否允许 "0"（上级 ID 表示顶级时）
     * @throws ServiceException ID 非法
     */
    public static void validateId(String value, boolean allowZero) {
        if (value == null || value.length() > 64 || !value.matches("[0-9]+")
            || (!allowZero && value.matches("0+")) || (allowZero && value.matches("0{2,}"))) {
            throw error(null, "业务 ID 非法");
        }
    }

    /**
     * 校验部门列表并按父先子后排序：ID 格式、重复 ID、跨分部、缺父、环；
     * 名称不为空且不超过 30 字、全称不超过 200 字、编码不超过 64 字；同级按 orderNum、id 排序后广度优先输出。
     *
     * @param subcompanyId 分部业务 ID
     * @param departments  来源返回的全部部门
     * @return 已校验快照
     * @throws ServiceException 组织图不完整或字段非法
     */
    public OrgDepartmentSnapshot validate(String subcompanyId, List<OrgDepartment> departments) {
        validateId(subcompanyId, false);
        Map<String, OrgDepartment> nodes = new HashMap<>();
        for (OrgDepartment department : departments) {
            if (department == null) {
                throw error(null, "空记录");
            }
            String id = department.id();
            validateId(id, false);
            validateId(department.subcompanyId(), false);
            validateId(department.parentId(), true);
            if (!subcompanyId.equals(department.subcompanyId())) {
                throw error(id, "跨分部记录");
            }
            if (department.name() == null || department.name().isBlank()) {
                throw error(id, "名称为空");
            }
            limited(department.name(), 30, id);
            limited(department.fullName(), 200, id);
            limited(department.code(), 64, id);
            if (department.orderNum() == null) {
                throw error(id, "排序非法");
            }
            if (nodes.putIfAbsent(id, department) != null) {
                throw error(id, "业务 ID 重复");
            }
        }
        Comparator<OrgDepartment> order = Comparator.comparing(OrgDepartment::orderNum)
            .thenComparing(OrgDepartment::id);
        Map<String, List<OrgDepartment>> children = new HashMap<>();
        for (OrgDepartment node : nodes.values()) {
            if (!"0".equals(node.parentId()) && !nodes.containsKey(node.parentId())) {
                throw error(node.id(), "上级不存在");
            }
            children.computeIfAbsent(node.parentId(), k -> new ArrayList<>()).add(node);
        }
        children.values().forEach(list -> list.sort(order));
        Deque<OrgDepartment> queue = new ArrayDeque<>(children.getOrDefault("0", List.of()));
        List<OrgDepartment> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            OrgDepartment node = queue.removeFirst();
            sorted.add(node);
            queue.addAll(children.getOrDefault(node.id(), List.of()));
        }
        if (sorted.size() != nodes.size()) {
            throw error(null, "组织关系存在环");
        }
        return new OrgDepartmentSnapshot(subcompanyId, sorted);
    }

    private static void limited(String value, int max, String id) {
        if (value != null && value.length() > max) {
            throw error(id, "字段超长");
        }
    }

    /**
     * 构造节点错误，只输出数字化的节点 ID，避免把来源原始内容带入提示。
     *
     * @param id     节点业务 ID，可为 null
     * @param reason 错误原因
     * @return 业务异常
     */
    private static ServiceException error(String id, String reason) {
        String safe = id == null ? "?" : id.replaceAll("[^0-9]", "?");
        return new ServiceException("组织节点 " + safe.substring(0, Math.min(64, safe.length())) + "：" + reason);
    }
}

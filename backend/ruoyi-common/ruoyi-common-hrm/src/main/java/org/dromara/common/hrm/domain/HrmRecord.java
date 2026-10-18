package org.dromara.common.hrm.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OA 原始记录，保留字段名、字符串值、空字符串及 XML null，不进行业务映射。
 *
 * @param fields 字段数据，不包含上游密码；人员信息只应在受控环境中查看
 */
public record HrmRecord(Map<String, String> fields) {

    /** 创建不可修改的字段快照，允许 XML null 值。 */
    public HrmRecord {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** 返回字段名称，避免日志输出人员字段值。 */
    @Override
    public String toString() {
        return "HrmRecord[fields=" + fields.keySet() + "]";
    }
}

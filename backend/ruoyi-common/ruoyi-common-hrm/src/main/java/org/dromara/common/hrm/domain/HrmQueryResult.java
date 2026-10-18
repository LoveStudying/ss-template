package org.dromara.common.hrm.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OA 查询结果；返回 null 与正常空数组必须分别判断。
 *
 * @param method 本次调用的 WebService 方法
 * @param nullResult 是否返回 xsi:nil，此时尚不能认定查询数据成功
 * @param records 原始记录，不含密码字段；保留 WSDL 允许的 null 数组项及其位置
 */
public record HrmQueryResult(String method, boolean nullResult, List<HrmRecord> records) {

    /** 创建不可修改的记录列表。 */
    public HrmQueryResult {
        records = Collections.unmodifiableList(new ArrayList<>(records));
    }

    /** 仅返回方法、null 状态和数量，避免日志泄露记录内容。 */
    @Override
    public String toString() {
        return "HrmQueryResult[method=" + method + ", nullResult=" + nullResult
            + ", count=" + records.size() + "]";
    }
}

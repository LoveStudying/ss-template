package org.dromara.common.hrm.domain.vo;

import org.dromara.common.hrm.domain.HrmQueryResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 对外查询结果；返回客户端获取的全部字段，保留 null 记录的位置。
 *
 * @param method 上游方法名
 * @param nullResult 上游整个 out 是否为 null
 * @param records 按原顺序排列的字段记录
 */
public record HrmQueryVo(String method, boolean nullResult, List<Map<String, String>> records) {

    /**
     * 从客户端结果创建包含全部已获取字段的响应，保留字段值及 null 记录位置。
     *
     * @param result 客户端查询结果，密码已在解析时丢弃
     * @return 不进行字段筛选的查询结果
     */
    public static HrmQueryVo from(HrmQueryResult result) {
        List<Map<String, String>> records = new ArrayList<>(result.records().size());
        result.records().forEach(record -> records.add(record == null ? null : record.fields()));
        return new HrmQueryVo(result.method(), result.nullResult(), Collections.unmodifiableList(records));
    }

}

package org.dromara.system.domain.sync;

import java.time.LocalDateTime;

/**
 * 外部组织来源中的分部，字段与具体来源格式无关。
 *
 * @param id             分部业务 ID，正整数串
 * @param parentId       上级分部业务 ID，顶级为 "0"
 * @param fullName       分部全称，不超过 200 字
 * @param code           分部编码，不超过 64 字
 * @param disabled       来源中是否已封存/停用
 * @param rawStatus      来源原始状态值，原样保留供落库
 * @param lastChangeTime 来源最后变更时间，未知为 null
 */
public record OrgSubcompany(String id, String parentId, String fullName, String code,
                            boolean disabled, String rawStatus, LocalDateTime lastChangeTime) {
}

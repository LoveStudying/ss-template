package org.dromara.system.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import org.apache.ibatis.annotations.Param;
import org.dromara.common.core.utils.StreamUtils;
import org.dromara.common.mybatis.annotation.DataColumn;
import org.dromara.common.mybatis.annotation.DataPermission;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.SysRole;
import org.dromara.system.domain.SysRoleDept;
import org.dromara.system.domain.vo.SysDeptVo;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.dromara.common.core.constant.SystemConstants.NORMAL;

/**
 * 部门管理 数据层
 *
 * @author Lion Li
 */
public interface SysDeptMapper extends BaseMapperPlus<SysDept, SysDeptVo>, MPJBaseMapper<SysDept> {

    /**
     * 按全局业务 ID 查询全部组织来源身份，包含逻辑删除记录，仅用于内部冲突校验。
     *
     * @param businessIds 业务 ID 集合，空集合不读取任何行
     * @return 匹配的部门行
     */
    List<SysDept> selectIdentityRows(@Param("businessIds") Collection<String> businessIds);

    /**
     * 查询相关节点、直接子节点及全部后代（仅未删除），空集合不会读取全表。
     *
     * @param localIds 本地部门 ID 集合
     * @return 相关部门行，按主键排序
     */
    List<SysDept> selectSourceRelatedRows(@Param("localIds") Collection<Long> localIds);

    /**
     * 按主键稳定顺序加行锁读取当前记录，包含删除记录用于身份重验。
     *
     * @param localIds 本地部门 ID 集合
     * @return 已锁定的部门行
     */
    List<SysDept> lockSourceDepartmentRows(@Param("localIds") Collection<Long> localIds);

    /**
     * 锁定父节点索引范围，防止检查后插入或搬入子部门。
     *
     * @param parentIds 父部门 ID 集合
     * @return 已锁定的未删除子部门行
     */
    List<SysDept> lockSourceChildren(@Param("parentIds") Collection<Long> parentIds);

    /**
     * 显式更新同步字段，支持清空可空快照，不修改本地联系方式和创建审计。
     *
     * @param row 目标部门快照
     * @return 更新行数
     */
    int updateSourceSnapshot(SysDept row);

    /**
     * 使用数据库排序规则检查最终同父级名称冲突。
     *
     * @param localIds 待检查的本地部门 ID 集合
     * @return 冲突部门 ID，至多一条
     */
    List<Long> selectSourceNameConflicts(@Param("localIds") Collection<Long> localIds);

    /**
     * 查询部门管理数据
     *
     * @param queryWrapper 查询条件
     * @return 部门信息集合
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id")
    })
    default List<SysDeptVo> selectDeptList(Wrapper<SysDept> queryWrapper) {
        return this.selectVoList(queryWrapper);
    }

    /**
     * 分页查询部门管理数据
     *
     * @param page         分页信息
     * @param queryWrapper 查询条件
     * @return 部门信息集合
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
    })
    default Page<SysDeptVo> selectPageDeptList(Page<SysDept> page, Wrapper<SysDept> queryWrapper) {
        return this.selectVoPage(page, queryWrapper);
    }

    /**
     * 统计指定部门ID的部门数量
     *
     * @param deptId 部门ID
     * @return 该部门ID的部门数量
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id")
    })
    default long countDeptById(Long deptId) {
        return this.lambda().eq(SysDept::getDeptId, deptId).count();
    }

    /**
     * 根据父部门ID查询其所有子部门的列表
     *
     * @param parentId 父部门ID
     * @return 包含子部门的列表
     */
    default List<SysDept> selectListByParentId(Long parentId) {
        return this.lambda()
            .select(SysDept::getDeptId)
            .findInSet(parentId, SysDept::getAncestors)
            .list();
    }

    /**
     * 查询某个部门及其所有子部门ID（含自身）
     *
     * @param parentId 父部门ID
     * @return 部门ID集合
     */
    default List<Long> selectDeptAndChildById(Long parentId) {
        List<SysDept> deptList = this.selectListByParentId(parentId);
        List<Long> deptIds = StreamUtils.toList(deptList, SysDept::getDeptId);
        deptIds.add(parentId);
        return deptIds;
    }

    /**
     * 根据角色ID查询部门树信息
     *
     * @param roleId            角色ID
     * @param deptCheckStrictly 部门树选择项是否关联显示
     * @return 选中部门列表
     */
    default List<Long> selectDeptListByRoleId(Long roleId, boolean deptCheckStrictly) {
        List<SysDept> depts = this.selectJoinList(SysDept.class, QueryBuilder.lambdaJoin("d", SysDept.class)
            .distinct()
            .select(SysDept::getDeptId, SysDept::getParentId, SysDept::getOrderNum)
            .leftJoin(SysRoleDept.class, "srd", SysRoleDept::getDeptId, SysDept::getDeptId)
            .leftJoin(SysRole.class, "sr", SysRole::getRoleId, SysRoleDept::getRoleId)
            .eq("srd", SysRoleDept::getRoleId, roleId)
            .eq("sr", SysRole::getStatus, NORMAL)
            .orderByAsc("d", SysDept::getParentId)
            .orderByAsc("d", SysDept::getOrderNum)
            .build());
        Set<Long> parentIds = deptCheckStrictly ? new HashSet<>(StreamUtils.toList(depts, SysDept::getParentId)) : Collections.emptySet();
        return depts.stream()
            .map(SysDept::getDeptId)
            .filter(deptId -> !parentIds.contains(deptId))
            .toList();
    }

}

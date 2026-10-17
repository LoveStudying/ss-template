package org.dromara.system.service.impl;

import org.dromara.common.core.exception.ServiceException;
import cn.hutool.extra.spring.SpringUtil;
import io.github.linpeilie.Converter;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.bo.SysDeptBo;
import org.dromara.system.mapper.SysDeptMapper;
import org.dromara.system.mapper.SysRoleMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证部门保存入口在缺少主键时仍返回业务提示而不是空指针异常。
 */
@DisplayName("部门保存缺少主键的容错测试")
class SysDeptServiceImplIdGuardTest {
    private final SysDeptMapper deptMapper = mock(SysDeptMapper.class);
    private final SysRoleMapper roleMapper = mock(SysRoleMapper.class);
    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final DeptBindingValidator bindingValidator = mock(DeptBindingValidator.class);
    private final SysDeptServiceImpl service =
        new SysDeptServiceImpl(deptMapper, roleMapper, userMapper, bindingValidator);

    @Test
    void insertWithoutParentIdReportsMissingParent() {
        when(deptMapper.lockSourceDepartmentRows(List.of())).thenReturn(List.of());
        SysDeptBo bo = new SysDeptBo();
        bo.setDeptName("新部门");
        assertThatThrownBy(() -> service.insertDept(bo))
            .isInstanceOf(ServiceException.class).hasMessage("父部门不存在");
        verify(deptMapper).lockSourceDepartmentRows(List.of());
    }

    @Test
    void updateWithoutDeptIdReportsMissingDept() {
        when(deptMapper.lockSourceDepartmentRows(List.of())).thenReturn(List.of());
        // MapstructUtils 静态初始化依赖 Spring 容器中的 Converter，这里以模拟容器提供转换器。
        Converter converter = mock(Converter.class);
        when(converter.convert(any(SysDeptBo.class), eq(SysDept.class))).thenReturn(new SysDept());
        try (var spring = mockStatic(SpringUtil.class)) {
            spring.when(() -> SpringUtil.getBean(Converter.class)).thenReturn(converter);
            SysDeptBo bo = new SysDeptBo();
            bo.setDeptName("改名");
            assertThatThrownBy(() -> service.updateDept(bo))
                .isInstanceOf(ServiceException.class).hasMessage("部门不存在，无法修改");
        }
        verify(deptMapper).lockSourceDepartmentRows(List.of());
    }
}

package com.jjy.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjy.infrastructure.persistence.po.RolePermissionPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermissionPO> {

    @Select("SELECT permission_id FROM t_role_permission WHERE role_id = #{roleId}")
    List<String> findPermissionIdsByRoleId(@Param("roleId") String roleId);

    @Select("SELECT DISTINCT permission_id FROM t_role_permission rp " +
            "JOIN t_role r ON rp.role_id = r.role_id " +
            "WHERE FIND_IN_SET(r.role_id, #{roleIds}) AND r.flag = 0")
    List<String> findPermissionIdsByRoleIds(@Param("roleIds") String roleIds);

    @Insert("INSERT INTO t_role_permission (role_id, permission_id) VALUES (#{roleId}, #{permissionId})")
    int insertOne(@Param("roleId") String roleId, @Param("permissionId") String permissionId);

    @Delete("DELETE FROM t_role_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") String roleId);
}

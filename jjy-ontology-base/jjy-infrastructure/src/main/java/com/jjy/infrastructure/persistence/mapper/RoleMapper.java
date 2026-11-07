package com.jjy.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjy.infrastructure.persistence.po.RolePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RoleMapper extends BaseMapper<RolePO> {

    @Select("SELECT * FROM t_role WHERE role_id = #{roleId} AND flag = 0")
    RolePO findByRoleId(@Param("roleId") String roleId);

    @Select("SELECT * FROM t_role WHERE role_code = #{roleCode} AND flag = 0")
    RolePO findByRoleCode(@Param("roleCode") String roleCode);

    @Select("SELECT * FROM t_role WHERE flag = 0 ORDER BY role_code")
    List<RolePO> findAll();

}

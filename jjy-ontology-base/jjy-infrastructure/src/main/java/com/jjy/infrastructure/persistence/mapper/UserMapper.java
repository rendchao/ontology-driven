package com.jjy.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjy.infrastructure.persistence.po.UserPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<UserPO> {

    @Select("SELECT * FROM t_user WHERE username = #{username} AND flag = 0")
    UserPO findByUsername(@Param("username") String username);

    @Select("SELECT * FROM t_user WHERE user_id = #{userId} AND flag = 0")
    UserPO findByUserId(@Param("userId") String userId);

    @Update("UPDATE t_user SET last_login_at = #{loginAt} WHERE user_id = #{userId}")
    int updateLastLoginAt(@Param("userId") String userId, @Param("loginAt") LocalDateTime loginAt);

    @Update("UPDATE t_user SET failed_login_count = #{count} WHERE user_id = #{userId}")
    int updateFailedLoginCount(@Param("userId") String userId, @Param("count") int count);

    @Update("UPDATE t_user SET locked_until = #{lockedUntil} WHERE user_id = #{userId}")
    int updateLockedUntil(@Param("userId") String userId, @Param("lockedUntil") LocalDateTime lockedUntil);

    @Update("UPDATE t_user SET password_hash = #{hash}, password_changed_at = #{changedAt}, " +
            "failed_login_count = 0, locked_until = NULL WHERE user_id = #{userId}")
    int updatePasswordHash(@Param("userId") String userId, @Param("hash") String hash, @Param("changedAt") LocalDateTime changedAt);

    /** 把 customerId 下所有用户禁用（租户暂停） */
    @Update("UPDATE t_user SET status = 'DISABLED', updated_at = NOW(3) WHERE customer_id = #{customerId} AND flag = 0")
    int disableByCustomerId(@Param("customerId") String customerId);

    /** 把 customerId 下所有用户恢复 ACTIVE（租户恢复） */
    @Update("UPDATE t_user SET status = 'ACTIVE', updated_at = NOW(3) WHERE customer_id = #{customerId} AND flag = 0")
    int enableByCustomerId(@Param("customerId") String customerId);

    /**
     * 列出拥有某角色的所有有效用户
     * t_user.role_ids 是 CSV (e.g. "ROLE-A,ROLE-B")，用 FIND_IN_SET 精确匹配
     */
    @Select("SELECT * FROM t_user WHERE FIND_IN_SET(#{roleId}, role_ids) > 0 AND flag = 0 ORDER BY username")
    List<UserPO> findByRoleId(@Param("roleId") String roleId);

    /** 统计某角色下的有效用户数 */
    @Select("SELECT COUNT(*) FROM t_user WHERE FIND_IN_SET(#{roleId}, role_ids) > 0 AND flag = 0")
    long countByRoleId(@Param("roleId") String roleId);

}

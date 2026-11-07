package com.jjy.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * t_role_permission 持久化对象（无 BaseAuditPO，无 flag/audit 字段）
 *
 * @author jjy-base
 */
@Data
@TableName("t_role_permission")
public class RolePermissionPO implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String roleId;
    private String permissionId;
}

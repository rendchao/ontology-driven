package com.jjy.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户-租户关联（多对多）
 * 1 个用户可访问多个租户；1 个租户可有多个用户
 *
 * @author jjy-base
 */
@Data
@TableName("t_user_tenant")
public class UserTenantPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String customerId;
    private Boolean isDefault;
    private String status;
    private String createdAt;

    @TableLogic
    private Integer flag;
}

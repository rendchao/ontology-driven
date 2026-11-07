package com.jjy.infrastructure.persistence.po.security;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * t_api_credential 持久化对象
 * <p>P6 M1 集成模型：外部系统 API Key + Secret 凭证
 * <p>P7 注：表结构无 created_by/updated_by，单独声明 flag + created_at/updated_at 字段</p>
 *
 * @author jjy-base
 */
@Data
@TableName("t_api_credential")
public class ApiCredentialPO implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** API Key（外部系统标识） */
    private String apiKey;

    /** API Secret（HMAC 签名密钥，可逆；生产建议 AES 加密存储） */
    private String apiSecret;

    /** 系统码：SAP / CUSTOMER / PORTAL / WMS / TMS / BMS / SIGNOFF / B2B */
    private String systemCode;

    /** 系统中文名 */
    private String systemName;

    private String description;

    /** 1=启用 0=禁用 */
    private Integer status;

    /** 到期时间（NULL=永不过期） */
    private LocalDateTime expiresAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableLogic(value = "0", delval = "1")
    @TableField("flag")
    private Integer flag;
}

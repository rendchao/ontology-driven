package com.jjy.infrastructure.persistence.po.integration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * t_inbound_request 持久化对象
 * <p>P6 M1 集成模型：REST_INBOUND 接收审计（8 个端点）</p>
 *
 * @author jjy-base
 */
@Data
@TableName("t_inbound_request")
public class InboundRequestPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 请求唯一 ID（header X-Request-Id 或自动生成） */
    private String requestUid;

    /** M1 接口 ID：INT-INB-001 / INT-OUT-001 等 */
    private String interfaceId;

    /** 系统码 */
    private String systemCode;

    /** 请求路径 */
    private String path;

    /** HTTP 方法 */
    private String httpMethod;

    /** 请求体 JSON 原文 */
    private String requestBody;

    /** 响应体 JSON 原文 */
    private String responseBody;

    /** HTTP 状态码（200/400/401/500） */
    private Integer responseCode;

    /** 业务返回 code（2000=OK / 4xxx/5xxx=ERR） */
    private Integer businessCode;

    /** 业务返回 message */
    private String businessMsg;

    /** 认证 API Key */
    private String apiKey;

    /** 收到的签名（截断） */
    private String signature;

    /** 客户端 IP */
    private String remoteIp;

    /** 处理耗时（毫秒） */
    private Long durationMs;

    /** 绑定的 M2 behaviorId */
    private String boundBehavior;

    private LocalDateTime createdAt;

    @TableLogic(value = "0", delval = "1")
    private Integer flag;
}

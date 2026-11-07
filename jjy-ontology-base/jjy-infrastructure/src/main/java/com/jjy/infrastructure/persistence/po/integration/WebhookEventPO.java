package com.jjy.infrastructure.persistence.po.integration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * t_webhook_event 持久化对象
 * <p>P6 M1 集成模型：EVENT_INBOUND webhook 事件（14 个端点，幂等 + 异步处理 + 重试）</p>
 *
 * @author jjy-base
 */
@Data
@TableName("t_webhook_event")
public class WebhookEventPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件唯一 ID（header X-Event-Id 或 payload.id+source） */
    private String eventUid;

    /** M1 接口 ID：INT-WMS-002 等 */
    private String interfaceId;

    /** 系统码 */
    private String systemCode;

    /** webhook 载荷 JSON 原文 */
    private String payload;

    /** 0=待处理 1=已处理 2=处理失败 3=死信 */
    private Integer status;

    private Integer retryCount;

    /** 映射到的内部事件：EVT-INB-004 等 */
    private String mappedEvent;

    /** 绑定的 M2 behaviorId（如果有） */
    private String boundBehavior;

    /** HTTP 状态码 */
    private Integer responseCode;

    private String errorMsg;

    private String signature;

    private String remoteIp;

    private LocalDateTime receivedAt;

    private LocalDateTime processedAt;

    private LocalDateTime nextRetryAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic(value = "0", delval = "1")
    private Integer flag;
}

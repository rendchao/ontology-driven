package com.jjy.infrastructure.event.outbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * event_outbox 表 PO（MyBatis-Plus）
 *
 * @author jjy-base
 */
@Data
@TableName("event_outbox")
public class Outbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventUid;
    private String aggregateType;
    private String aggregateId;
    private String eventType;

    /** JSON 字符串，载荷 */
    private String payload;

    /** 0=待发 1=已发 2=失败 3=死信 */
    private Integer status;

    private Integer retryCount;

    @TableField("next_retry_at")
    private LocalDateTime nextRetryAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("sent_at")
    private LocalDateTime sentAt;
}

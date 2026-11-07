package com.jjy.infrastructure.event.idempotent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * event_handler_log 表 PO
 *
 * @author jjy-base
 */
@Data
@TableName("event_handler_log")
public class EventHandlerLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventUid;

    /** 订阅者标识（行为 ID / 规则 ID / 场景 ID） */
    private String handler;

    private LocalDateTime processedAt;

    /** 0=成功 1=失败 */
    private Integer result;

    private String errorMsg;
}

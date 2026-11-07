package com.jjy.infrastructure.event.deadletter;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * event_dead_letter 表 PO
 *
 * @author jjy-base
 */
@Data
@TableName("event_dead_letter")
public class DeadLetter {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventUid;
    private String handler;
    private String payload;
    private String failureMsg;

    @TableField("resolved_flag")
    private Integer resolvedFlag;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

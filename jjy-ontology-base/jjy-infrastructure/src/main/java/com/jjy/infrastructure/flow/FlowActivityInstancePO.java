package com.jjy.infrastructure.flow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * M6 activity 执行历史（append-only 审计）。
 *
 * <p>一行 = 一次 activity 执行。finished_at 在异常路径也填，便于事后追查。
 * result_status 字段对 GATEWAY 写"分支名"，对 USER_TASK/APPROVAL_TASK 写 BehaviorResult.status。</p>
 */
@Data
@NoArgsConstructor
@TableName("flow_activity_instance")
public class FlowActivityInstancePO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("flow_instance_id")
    private String flowInstanceId;

    @TableField("activity_id")
    private String activityId;

    @TableField("activity_type")
    private String activityType;

    @TableField("behavior_ref")
    private String behaviorRef;

    @TableField("result_status")
    private String resultStatus;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField("error_msg")
    private String errorMsg;
}

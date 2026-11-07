package com.jjy.infrastructure.flow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * M6 流程实例 PO（对应 flow_instance 表，V34 创建）。
 *
 * <p>context 是 JSON 字段：跨 activity 共享状态（input + 上一步 result.status 等）。
 * 这里存的是 P5 范围内同步 flow 走完所需的最小信息；GATEWAY 分支命中也写在这里。</p>
 */
@Data
@NoArgsConstructor
@TableName(value = "flow_instance", autoResultMap = true)
public class FlowInstancePO {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("flow_id")
    private String flowId;

    @TableField("status")
    private String status;

    @TableField("current_activity")
    private String currentActivity;

    @TableField(value = "context", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> context = new HashMap<>();

    @TableField("error_msg")
    private String errorMsg;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

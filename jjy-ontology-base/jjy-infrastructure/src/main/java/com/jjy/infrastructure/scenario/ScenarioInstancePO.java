package com.jjy.infrastructure.scenario;

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
 * M4 场景实例 PO（对应 scenario_instance 表，V1 已建）。
 *
 * <p>context 是 JSON 字段：跨步骤共享数据 + 等待中的 event_payload + 上次 step 的输出等。</p>
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
@TableName(value = "scenario_instance", autoResultMap = true)
public class ScenarioInstancePO {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("scenario_id")
    private String scenarioId;

    @TableField("status")
    private String status;

    /** 步骤间共享上下文（入参 + 步骤输出 + 等待中的 event payload） */
    @TableField(value = "context", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> context = new HashMap<>();

    /** 当前步骤 ID（WAITING 时 = 等待 event 的步骤） */
    @TableField("current_step")
    private String currentStep;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

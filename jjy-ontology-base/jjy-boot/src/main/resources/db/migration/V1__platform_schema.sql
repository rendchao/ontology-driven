-- ═══════════════════════════════════════════════════════════════════════════
-- jjy 底座 —— 平台表结构 (Platform Schema)
--
-- 本文件只含「底座自带」的平台表：事件机制 / 流程 / 场景 / 用户 / 角色 / 菜单 /
-- 权限 / 审计 / 字典 / 凭证。
--
-- 业务表不在这里 —— 它们由 models/m1-object-model.yaml + models/mm-mapping-model.yaml
-- 经 codegen-maven-plugin 在 generate-sources 阶段生成对应 DDL，
-- 保持「模型是唯一语义来源」纪律。
-- ═══════════════════════════════════════════════════════════════════════════

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS `event_dead_letter` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_uid` varchar(64) NOT NULL,
  `handler` varchar(128) NULL,
  `payload` json NULL,
  `failure_msg` text NULL,
  `resolved_flag` tinyint NOT NULL DEFAULT 0 COMMENT '0=未处理 1=已人工核销',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_event` (`event_uid`),
  KEY `idx_resolved` (`resolved_flag`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='死信';

CREATE TABLE IF NOT EXISTS `event_handler_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_uid` varchar(64) NOT NULL,
  `handler` varchar(128) NOT NULL COMMENT '订阅者标识（行为 ID / 规则 ID / 场景 ID）',
  `processed_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `result` tinyint NOT NULL DEFAULT 0 COMMENT '0=成功 1=失败',
  `error_msg` varchar(2000) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_handler` (`event_uid`, `handler`),
  KEY `idx_handler_processed` (`handler`, `processed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消费幂等日志';

CREATE TABLE IF NOT EXISTS `event_outbox` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_uid` varchar(64) NOT NULL COMMENT '业务侧 UUID，用于幂等',
  `aggregate_type` varchar(64) NOT NULL COMMENT '聚合根 alias，如 Customer',
  `aggregate_id` varchar(64) NOT NULL COMMENT '聚合根 ID',
  `event_type` varchar(64) NOT NULL COMMENT '事件 ID，如 EVT-CUS-002',
  `payload` json NOT NULL COMMENT '事件载荷（按 ME.payload）',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0=待发 1=已发 2=失败 3=死信',
  `retry_count` int NOT NULL DEFAULT 0,
  `next_retry_at` datetime(3) NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `sent_at` datetime(3) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_uid` (`event_uid`),
  KEY `idx_status_next_retry` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件发件箱';

CREATE TABLE IF NOT EXISTS `flow_activity_instance` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `flow_instance_id` varchar(64) NOT NULL COMMENT '关联 flow_instance.id',
  `activity_id` varchar(64) NOT NULL COMMENT 'M6 activityId，如 A02',
  `activity_type` varchar(32) NOT NULL COMMENT 'START / END / USER_TASK / APPROVAL_TASK / GATEWAY / BEHAVIOR_CALL',
  `behavior_ref` varchar(64) NULL COMMENT '执行的 M2 behaviorId（GATEWAY 留空）',
  `result_status` varchar(64) NULL COMMENT 'BehaviorResult.status（GATEWAY 写分支命中的 branchName）',
  `started_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `finished_at` datetime(3) NULL COMMENT '执行结束时间（异常也填）',
  `error_msg` varchar(2000) NULL COMMENT '单 activity 异常信息',
  PRIMARY KEY (`id`),
  KEY `idx_flow_instance` (`flow_instance_id`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='M6 activity 执行历史';

CREATE TABLE IF NOT EXISTS `flow_instance` (
  `id` varchar(64) NOT NULL COMMENT 'UUID',
  `flow_id` varchar(64) NOT NULL COMMENT 'M6 flowId，如 FLOW-LOSS-001',
  `status` varchar(16) NOT NULL COMMENT '见 FlowStatus',
  `current_activity` varchar(64) NULL COMMENT '当前正在执行的 activityId',
  `context` json NULL COMMENT 'binding：跨 activity 共享状态（input + 上一步 result.status 等）',
  `error_msg` varchar(2000) NULL COMMENT 'FAILED 时的错误信息',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_flow_status` (`flow_id`, `status`),
  KEY `idx_status_curr` (`status`, `current_activity`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='M6 流程实例';

CREATE TABLE IF NOT EXISTS `scenario_instance` (
  `id` varchar(64) NOT NULL COMMENT 'UUID',
  `scenario_id` varchar(64) NOT NULL COMMENT 'M4 scenarioId',
  `status` varchar(16) NOT NULL,
  `context` json NULL COMMENT '场景上下文（步骤间共享数据）',
  `current_step` varchar(64) NULL COMMENT '当前步骤 ID',
  `error_msg` varchar(2000) NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_scenario_status` (`scenario_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='M4 场景实例';

CREATE TABLE IF NOT EXISTS `t_api_credential` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `api_key` varchar(64) NOT NULL COMMENT 'API Key（外部系统标识）',
  `api_secret` varchar(128) NOT NULL COMMENT 'API Secret（HMAC 签名密钥，需可逆）',
  `system_code` varchar(32) NOT NULL COMMENT '系统码：SAP/CUSTOMER/PORTAL/WMS/TMS/BMS/SIGNOFF/B2B',
  `system_name` varchar(64) NOT NULL COMMENT '系统中文名',
  `description` varchar(200) NULL,
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1=启用 0=禁用',
  `expires_at` datetime(3) NULL COMMENT '到期时间（NULL=永不过期）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `flag` tinyint NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_api_key` (`api_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='外部系统 API 凭证';

CREATE TABLE IF NOT EXISTS `t_data_dictionary` (
  `dictionary_id` varchar(64) NOT NULL,
  `description` varchar(500) NULL,
  `created_at` datetime(3) NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`dictionary_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据字典';

CREATE TABLE IF NOT EXISTS `t_data_dictionary_item` (
  `dictionary_id` varchar(64) NOT NULL,
  `type_code` varchar(64) NOT NULL,
  `type_name` varchar(200) NOT NULL,
  `code` varchar(64) NOT NULL,
  `label` varchar(200) NOT NULL,
  `enabled` tinyint(1) NULL DEFAULT 1,
  `sort_order` int NULL DEFAULT 0,
  `created_at` datetime(3) NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`dictionary_id`, `type_code`, `code`),
  KEY `idx_type_code` (`type_code`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据字典项';

CREATE TABLE IF NOT EXISTS `t_menu` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '物理主键',
  `code` varchar(64) NOT NULL COMMENT '资源码（业务主键）：menu.dashboard / menu.main.customer / btn.customer.create',
  `name` varchar(64) NOT NULL COMMENT '显示名',
  `type` varchar(16) NOT NULL COMMENT 'GROUP / MENU / BUTTON',
  `parent_id` bigint NULL COMMENT '父节点 ID（顶级为 NULL）',
  `path` varchar(200) NULL COMMENT '前端路由 path（MENU 才有）',
  `icon` varchar(32) NULL COMMENT '图标（MENU/GROUP 才有）',
  `sort` int NOT NULL DEFAULT 0 COMMENT '同级排序',
  `permission` varchar(500) NULL COMMENT '准入权限码 CSV（OR 关系；NULL=无限制；*=超管专属）',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `created_by` varchar(64) NOT NULL DEFAULT 'SYSTEM',
  `flag` tinyint NOT NULL DEFAULT 0 COMMENT '@TableLogic 0=有效 1=删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_parent` (`parent_id`),
  KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资源菜单表（GROUP+MENU+BUTTON 统一管理；准入权限 = permission）';

CREATE TABLE IF NOT EXISTS `t_role_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_id` varchar(64) NOT NULL,
  `permission_id` varchar(128) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `permission_id`),
  KEY `idx_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联';

CREATE TABLE IF NOT EXISTS `t_rule_fire_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `fired_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '评估时间',
  `tenant_id` varchar(64) NULL COMMENT '租户（多租户隔离）',
  `behavior_id` varchar(64) NOT NULL COMMENT 'M2 行为 ID，如 Order_Approve',
  `behavior_name` varchar(200) NULL COMMENT '行为名（冗余便于列表展示）',
  `aggregate_type` varchar(64) NULL COMMENT '聚合根：Order / Customer 等',
  `aggregate_id` varchar(64) NULL COMMENT '业务主键',
  `rule_id` varchar(64) NOT NULL COMMENT 'M3 规则 ID，如 RULE-INB-001',
  `rule_name` varchar(200) NULL COMMENT '规则名（冗余）',
  `rule_type` varchar(32) NULL COMMENT 'VALIDATION / EVENT_DRIVEN / CALCULATION',
  `engine` varchar(16) NULL COMMENT 'AVIATOR / SPEL',
  `expression` text NULL COMMENT '规则表达式原文',
  `result` varchar(16) NOT NULL COMMENT 'PASS / FAIL / SKIP',
  `output` tinyint NULL COMMENT '实际布尔值（SKIP 时为 NULL）',
  `input_params` json NULL COMMENT '评估入参 map（已脱敏 phone/email）',
  `violation_message` varchar(2000) NULL COMMENT '失败时的违规消息',
  `actor_user_id` varchar(64) NULL COMMENT '触发人',
  `trace_id` varchar(64) NULL COMMENT '请求级链路 ID（来自 TraceIdFilter MDC）',
  `created_by` varchar(64) NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_by` varchar(64) NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `flag` tinyint NOT NULL DEFAULT 0 COMMENT '0=有效 1=无效',
  PRIMARY KEY (`id`),
  KEY `idx_agg` (`aggregate_type`, `aggregate_id`, `fired_at`),
  KEY `idx_behavior_fired` (`behavior_id`, `fired_at`),
  KEY `idx_result_fired` (`result`, `fired_at`),
  KEY `idx_rule_fired` (`rule_id`, `fired_at`),
  KEY `idx_tenant_fired` (`tenant_id`, `fired_at`),
  KEY `idx_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='M3 规则触发日志（业务白盒化）';

CREATE TABLE IF NOT EXISTS `t_user_tenant` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` varchar(64) NOT NULL COMMENT '用户 id',
  `customer_id` varchar(64) NOT NULL COMMENT '租户 id',
  `is_default` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否默认',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/DISABLED',
  `flag` tinyint(1) NOT NULL DEFAULT 0 COMMENT '软删标志 0=有效 1=已删除',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_tenant` (`user_id`, `customer_id`),
  KEY `idx_customer` (`customer_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户-租户关联（多对多）';

CREATE TABLE IF NOT EXISTS `t_user` (
  `user_id` varchar(64) NOT NULL,
  `username` varchar(64) NOT NULL,
  `password_hash` varchar(256) NOT NULL,
  `full_name` varchar(64) NOT NULL,
  `user_type` varchar(16) NOT NULL COMMENT 'PLATFORM/CUSTOMER/API',
  `customer_id` varchar(64) NULL,
  `role_ids` varchar(500) NOT NULL COMMENT '逗号分隔',
  `phone` varchar(32) NULL,
  `email` varchar(128) NULL,
  `status` varchar(16) NOT NULL,
  `last_login_at` datetime(3) NULL,
  `password_changed_at` datetime(3) NULL,
  `failed_login_count` int NULL DEFAULT 0,
  `locked_until` datetime(3) NULL,
  `created_by` varchar(64) NULL,
  `created_at` datetime(3) NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_by` varchar(64) NULL,
  `updated_at` datetime(3) NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `flag` smallint NULL DEFAULT 0,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `username` (`username`),
  KEY `idx_customer` (`customer_id`, `flag`),
  KEY `idx_type_status` (`user_type`, `status`, `flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户';

CREATE TABLE IF NOT EXISTS `t_role` (
  `role_id` varchar(64) NOT NULL,
  `role_code` varchar(64) NOT NULL,
  `role_name` varchar(64) NOT NULL,
  `role_type` varchar(16) NOT NULL COMMENT 'PLATFORM/CUSTOMER',
  `permission_ids` text NOT NULL COMMENT '逗号分隔的权限 ID',
  `status` varchar(16) NOT NULL,
  `description` varchar(500) NULL,
  `created_by` varchar(64) NULL,
  `created_at` datetime(3) NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_by` varchar(64) NULL,
  `updated_at` datetime(3) NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `flag` smallint NULL DEFAULT 0,
  PRIMARY KEY (`role_id`),
  UNIQUE KEY `role_code` (`role_code`),
  KEY `idx_type_status` (`role_type`, `status`, `flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色';

CREATE TABLE IF NOT EXISTS `t_audit_log` (
  `log_id` varchar(64) NOT NULL,
  `actor_id` varchar(64) NOT NULL,
  `actor_type` varchar(16) NULL,
  `action` varchar(64) NOT NULL,
  `target_type` varchar(32) NOT NULL,
  `target_id` varchar(64) NOT NULL,
  `before_snapshot` text NULL,
  `after_snapshot` text NULL,
  `ip_address` varchar(64) NULL,
  `user_agent` varchar(500) NULL,
  `trace_id` varchar(64) NULL COMMENT '请求级链路 ID（来自 TraceIdFilter MDC）',
  `operation_time` datetime(3) NOT NULL,
  `created_by` varchar(64) NULL,
  `created_at` datetime(3) NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_by` varchar(64) NULL,
  `updated_at` datetime(3) NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `flag` smallint NULL DEFAULT 0,
  PRIMARY KEY (`log_id`),
  KEY `idx_actor` (`actor_id`, `operation_time`),
  KEY `idx_target` (`target_type`, `target_id`),
  KEY `idx_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志';

CREATE TABLE IF NOT EXISTS `t_webhook_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_uid` varchar(64) NOT NULL COMMENT '事件唯一 ID（header X-Event-Id 或 payload.id+source）',
  `interface_id` varchar(32) NOT NULL COMMENT 'M1 接口 ID：INT-WMS-002 等',
  `system_code` varchar(32) NOT NULL COMMENT '系统码',
  `payload` mediumtext NOT NULL COMMENT 'webhook 载荷 JSON 原文',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0=待处理 1=已处理 2=处理失败 3=死信',
  `retry_count` int NOT NULL DEFAULT 0,
  `mapped_event` varchar(64) NULL COMMENT '映射到的内部事件：EVT-INB-004 等',
  `bound_behavior` varchar(64) NULL COMMENT '绑定的 M2 behaviorId（如果有）',
  `response_code` int NULL COMMENT 'HTTP 状态码',
  `error_msg` varchar(2000) NULL,
  `signature` varchar(128) NULL,
  `remote_ip` varchar(64) NULL,
  `received_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `processed_at` datetime(3) NULL,
  `next_retry_at` datetime(3) NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `flag` tinyint NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_uid` (`event_uid`),
  KEY `idx_interface_id` (`interface_id`),
  KEY `idx_received_at` (`received_at`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='EVENT_INBOUND webhook 事件';

-- ═══════════════════════════════════════════════════════════════
-- 业务表（由 M1 对象模型 + MM 映射模型生成）
--
-- 这两张表由 codegen-maven-plugin 在构建期从
--   models/m1-object-model.yaml + models/mm-mapping-model.yaml
-- 渲染出目标态 DDL（target/generated-sources/ddl/）。
-- 本迁移文件是「物理结构快照」，与生成结果保持一致；
-- 漂移由 scripts/check-model-drift.ps1 与 MM↔DB 对账兜底。
--
-- 业务工程替换模型后：重新生成 → 同步本文件 → 重建库。
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS `t_product` (
  `product_id` varchar(64) NOT NULL COMMENT '主键（UUID）',
  `product_no` varchar(64) NOT NULL COMMENT '商品编号',
  `product_name` varchar(200) NOT NULL COMMENT '商品名称',
  `category` varchar(64) NULL COMMENT '商品分类',
  `unit` varchar(32) NULL COMMENT '计量单位',
  `price` decimal(18,2) NULL COMMENT '单价',
  `status` varchar(16) NULL COMMENT '状态（DICT-PRODUCT-STATUS code）',
  `created_by` varchar(64) NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by` varchar(64) NULL,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `flag` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`product_id`),
  UNIQUE KEY `uk_product_no` (`product_no`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品';

CREATE TABLE IF NOT EXISTS `t_product_attribute` (
  `attribute_id` varchar(64) NOT NULL COMMENT '属性ID',
  `product_id` varchar(64) NULL COMMENT '外键 → t_product',
  `attribute_name` varchar(128) NOT NULL COMMENT '属性名',
  `attribute_value` varchar(512) NULL COMMENT '属性值',
  `created_by` varchar(64) NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by` varchar(64) NULL,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `flag` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`attribute_id`),
  KEY `idx_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品属性';

SET FOREIGN_KEY_CHECKS = 1;

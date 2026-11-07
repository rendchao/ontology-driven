package com.jjy.infrastructure.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 对话系统配置（jjy.ai.*）
 *
 * <p>所有配置都有合理默认值；ai.enabled=false 时整个对话系统不挂载，避免无 LLM 也能启动。</p>
 *
 * <pre>
 * jjy:
 *   ai:
 *     enabled: true
 *     provider: openai-compatible
 *     base-url: https://api.openai.com/v1
 *     api-key: sk-xxx
 *     model: gpt-4o-mini
 *     temperature: 0.2
 *     max-tokens: 4096
 *     timeout-seconds: 60
 *     max-tool-rounds: 6
 *     sql-whitelist-tables:
 *       - t_data_dictionary
 *       - t_inventory_mirror
 *       - ...
 * </pre>
 *
 * @author jjy-base
 */
@Data
@ConfigurationProperties(prefix = "jjy.ai")
public class AiProperties {

    /** 总开关（false 时 AiChatController 不注册） */
    private boolean enabled = false;

    /** 提供方：openai-compatible（默认）/azure-openai/ollama（仅本机 dev 用） */
    private String provider = "openai-compatible";

    /** OpenAI 兼容 API 基础 URL（v1/chat/completions 接在后面） */
    private String baseUrl = "https://api.openai.com/v1";

    /** LLM API key */
    private String apiKey = "";

    /** 模型名（gpt-4o-mini / deepseek-chat / qwen-turbo 等） */
    private String model = "gpt-4o-mini";

    /** 采样温度，0-2；推荐 0.1-0.3 保证稳定性 */
    private double temperature = 0.2;

    /** 单次响应最大 token 数 */
    private int maxTokens = 4096;

    /** HTTP 调用超时（秒） */
    private int timeoutSeconds = 60;

    /** 单次对话最大 tool-call 轮次（防死循环） */
    private int maxToolRounds = 6;

    /** 允许 AI 工具直接 SELECT 的表白名单（SqlSafetyValidator 用） */
    private List<String> sqlWhitelistTables = new ArrayList<>();

    /** 角色权限降级策略：true=AI 看到的是调用者的真实权限（推荐） */
    private boolean enforceCallerPermissions = true;

    /** 是否把对话写入 t_audit_log */
    private boolean auditEnabled = true;
}

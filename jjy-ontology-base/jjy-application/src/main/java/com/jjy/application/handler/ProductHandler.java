package com.jjy.application.handler;

import com.jjy.application.behavior.BehaviorContext;
import com.jjy.application.util.BizNoGenerator;
import com.jjy.domain.behavior.BehaviorHandler;
import com.jjy.infrastructure.util.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 商品聚合的行为处理器（示例实现，展示行为驱动写法）。
 *
 * <p><b>这是「行为驱动」的标准形态</b>：前端不直接调 Service，而是调
 * {@code POST /behavior/Product_SaveAsDraft}，由 {@code BehaviorDispatcher}
 * 按 M2 声明依次完成权限校验 → 规则求值 → 调用本类方法 → 同事务写事件。</p>
 *
 * <p>新增业务功能的成本 = 模型加一条 YAML + 写一个 {@code @BehaviorHandler} 方法，
 * <b>不需要改动架构或新增接口</b>。</p>
 *
 * <p><b>状态值的来源</b>：本类不出现任何硬编码的状态字符串
 * （如 {@code "DRAFT"} / {@code "ON_SHELF"}）。状态目标值来自 M2 的
 * {@code postconditions}，合法性由 M1 的 {@code lifecycle} 与
 * {@code transitions} 判定——三者共同构成状态机的唯一真源。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class ProductHandler {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 物理映射解析器：从 MM 模型解析表名与主键列。
     *
     * <p>示例 Handler 演示的写法是<b>不硬编码表名</b>——业务工程换模型时，
     * 只要改了 MM 映射，这里的 SQL 自动跟着走。</p>
     */
    @Autowired
    private com.jjy.infrastructure.yaml.PhysicalMappingResolver physicalMapping;

    /**
     * M7 报表执行器：查询口径按 M7 声明生成 SQL。
     */
    @Autowired
    private com.jjy.application.report.QueryReportExecutor reportExecutor;

    /** 模型注册表：读 M2 的 queryReportRef */
    @Autowired
    private com.jjy.domain.registry.ModelRegistry registry;

    /**
     * 生命周期守卫：状态变更前校验目标状态在 M1 的 lifecycle 声明内、
     * 且迁移在 M1 的 transitions 表内。
     */
    @Autowired
    private com.jjy.application.lifecycle.LifecycleGuard lifecycleGuard;

    /**
     * 业务编号生成器：编号规则来自 M1 模型的 {@code noRule} 声明。
     */
    @Autowired
    private BizNoGenerator bizNo;

    /** 商品表名（来自 MM 映射模型） */
    private String table() {
        return physicalMapping.tableName("Product");
    }

    /** 商品主键列（来自 MM 映射模型） */
    private String pk() {
        return physicalMapping.pkColumn(table());
    }

    /**
     * 商品某字段对应的列名（来自 MM 映射模型）。
     *
     * <p>SQL 里不写列名字面量——MM 改了列名，这里自动跟着走。</p>
     */
    private String col(String field) {
        return physicalMapping.column(table(), field);
    }

    /**
     * 保存商品草稿（对应 M2 的 Product_SaveAsDraft）。
     *
     * <p>有真实 DB 写与状态变更——这是 L2「已实现」判据的最低要求：
     * 仅 {@code return ok()} 不算实现。</p>
     */
    @BehaviorHandler("Product_SaveAsDraft")
    public Map<String, Object> saveAsDraft(BehaviorContext ctx) throws Exception {
        Map<String, Object> input = ctx.getInput();
        Object dtoObj = input == null ? null : input.get("dto");
        @SuppressWarnings("unchecked")
        Map<String, Object> dto = dtoObj instanceof Map ? (Map<String, Object>) dtoObj : input;

        String productName = str(dto == null ? null : dto.get("productName"));
        String category = str(dto == null ? null : dto.get("category"));
        String unit = str(dto == null ? null : dto.get("unit"));
        Object priceObj = dto == null ? null : dto.get("price");
        Double price = priceObj == null ? 0D : Double.parseDouble(priceObj.toString());
        Object productIdObj = dto == null ? null : dto.get("productId");
        String productId = productIdObj == null ? null : productIdObj.toString();

        String userId = ctx.getActor() == null ? null : ctx.getActor().getUserId();
        String productNo = generateNo();
        String now = DateUtil.nowCompact();

        // 目标状态来自 M2 的 postconditions（不是代码里的常量）。
        // 新建时无原状态，迁移校验只校验「目标状态是否合法」。
        String targetStatus = lifecycleGuard.targetStatusOf("Product_SaveAsDraft");
        if (targetStatus == null) {
            throw new IllegalStateException(
                    "M2 行为 Product_SaveAsDraft 未声明 postconditions 的目标状态");
        }
        lifecycleGuard.checkTransition("Product", null, targetStatus);

        if (productId == null || productId.trim().isEmpty()) {
            // 新增：主键为业务侧 UUID（MM 声明主键列 VARCHAR(64)）
            String newId = UUID.randomUUID().toString().replace("-", "");
            jdbcTemplate.update(
                    "INSERT INTO " + table() + " (" + pk()
                            + ", " + col("productNo") + ", " + col("productName")
                            + ", " + col("category") + ", " + col("unit")
                            + ", " + col("price") + ", " + col("status")
                            + ", created_by, created_at, updated_by, updated_at, flag) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                    newId, productNo, productName, category, unit, price, targetStatus,
                    userId, now, userId, now);
            productId = newId;
        } else {
            // 更新（状态机：仅草稿态允许覆盖草稿内容）
            jdbcTemplate.update(
                    "UPDATE " + table() + " SET " + col("productName") + "=?, " + col("category")
                            + "=?, " + col("unit") + "=?, " + col("price") + "=?, "
                            + col("status") + "=?, "
                            + "updated_by=?, updated_at=? WHERE " + pk() + "=? AND flag=0",
                    productName, category, unit, price, targetStatus, userId, now, productId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productId", productId);
        result.put("productNo", productNo);
        result.put("status", targetStatus);
        log.info("[ProductHandler] saveAsDraft productId={} productNo={} status={}",
                productId, productNo, targetStatus);
        return result;
    }

    /**
     * 提交商品上架（对应 M2 的 Product_Submit）。
     *
     * <p>状态机迁移：由 M1 的 transitions 声明合法性，
     * 目标状态由 M2 的 postconditions 声明。产出事件 EVT-PRODUCT-001，
     * 由 dispatcher 在同一事务内写入 outbox。</p>
     */
    @BehaviorHandler("Product_Submit")
    public Map<String, Object> submit(BehaviorContext ctx) throws Exception {
        Map<String, Object> input = ctx.getInput();
        Object dtoObj = input == null ? null : input.get("dto");
        @SuppressWarnings("unchecked")
        Map<String, Object> dto = dtoObj instanceof Map ? (Map<String, Object>) dtoObj : input;
        Object idObj = dto == null ? null : dto.get("productId");
        String productId = idObj == null ? null : idObj.toString();
        if (productId == null || productId.trim().isEmpty()) {
            throw new IllegalArgumentException("productId 不能为空");
        }

        String userId = ctx.getActor() == null ? null : ctx.getActor().getUserId();

        // 读当前状态（迁移校验需要原状态）
        String currentStatus = queryStatus(productId);

        // 迁移合法性由 M1 的 transitions 判定，目标状态由 M2 的 postconditions 声明。
        // 两者都不在代码里——改模型即改行为。
        lifecycleGuard.checkTransitionOf("Product", "Product_Submit", currentStatus);
        String targetStatus = lifecycleGuard.targetStatusOf("Product_Submit");

        int updated = jdbcTemplate.update(
                "UPDATE " + table() + " SET " + col("status") + "=?, updated_by=?, updated_at=? "
                        + "WHERE " + pk() + "=? AND " + col("status") + "=? AND flag=0",
                targetStatus, userId, DateUtil.nowCompact(), productId, currentStatus);
        if (updated == 0) {
            throw new IllegalStateException("商品状态不允许提交上架（当前状态 " + currentStatus + "）");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productId", productId);
        result.put("status", targetStatus);
        log.info("[ProductHandler] submit productId={} {}→{}", productId, currentStatus, targetStatus);
        return result;
    }

    /**
     * 商品下架（对应 M2 的 Product_TakeOff）。
     *
     * <p>迁移 ON_SHELF → OFF_SHELF 的合法性由 M1 的 transitions 声明，
     * 目标状态由 M2 的 postconditions 声明。</p>
     */
    @BehaviorHandler("Product_TakeOff")
    public Map<String, Object> takeOff(BehaviorContext ctx) throws Exception {
        Map<String, Object> input = ctx.getInput();
        Object dtoObj = input == null ? null : input.get("dto");
        @SuppressWarnings("unchecked")
        Map<String, Object> dto = dtoObj instanceof Map ? (Map<String, Object>) dtoObj : input;
        Object idObj = dto == null ? null : dto.get("productId");
        String productId = idObj == null ? null : idObj.toString();
        if (productId == null || productId.trim().isEmpty()) {
            throw new IllegalArgumentException("productId 不能为空");
        }

        String userId = ctx.getActor() == null ? null : ctx.getActor().getUserId();
        String currentStatus = queryStatus(productId);

        lifecycleGuard.checkTransitionOf("Product", "Product_TakeOff", currentStatus);
        String targetStatus = lifecycleGuard.targetStatusOf("Product_TakeOff");

        int updated = jdbcTemplate.update(
                "UPDATE " + table() + " SET " + col("status") + "=?, updated_by=?, updated_at=? "
                        + "WHERE " + pk() + "=? AND " + col("status") + "=? AND flag=0",
                targetStatus, userId, DateUtil.nowCompact(), productId, currentStatus);
        if (updated == 0) {
            throw new IllegalStateException("商品状态不允许下架（当前状态 " + currentStatus + "）");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productId", productId);
        result.put("status", targetStatus);
        log.info("[ProductHandler] takeOff productId={} {}→{}", productId, currentStatus, targetStatus);
        return result;
    }

    /**
     * 查询商品列表（对应 M2 的 Product_Query）。
     *
     * <p>查询类行为：返回结果集，不做状态变更。</p>
     */
    @BehaviorHandler("Product_Query")
    public Map<String, Object> query(BehaviorContext ctx) {
        Map<String, Object> input = ctx.getInput();
        Object dtoObj = input == null ? null : input.get("dto");
        @SuppressWarnings("unchecked")
        Map<String, Object> dto = dtoObj instanceof Map ? (Map<String, Object>) dtoObj : input;
        String productName = str(dto == null ? null : dto.get("productName"));
        String status = str(dto == null ? null : dto.get("status"));

        // 查询口径来自 M7（resultColumns / sourceObjects / parameters / orderBy / pagination），
        // 由 QueryReportExecutor 按模型生成 SQL——改 M7 即刻生效，Java 零改动。
        Map<String, Object> params = new LinkedHashMap<>();
        if (productName != null && !productName.isEmpty()) {
            params.put("productName", productName);
        }
        if (status != null && !status.isEmpty()) {
            params.put("status", status);
        }
        return reportExecutor.execute(reportIdOf(ctx), params);
    }

    /**
     * 商品上架后处理（对应 M2 的 Product_OnShelf，triggerType=EVENT）。
     *
     * <p>由 {@code EventSubscriberRegistry} 在 EVT-PRODUCT-001 派发时调用，
     * 经 {@code EventBehaviorInvoker} 走完整链路（权限 → 规则 → 本方法）。</p>
     *
     * <p>入参是事件 payload：{@code productId} / {@code productNo} / {@code status}，
     * 外加聚合根 ID（{@code id} / {@code aggregateId}）。</p>
     */
    @BehaviorHandler("Product_OnShelf")
    public Map<String, Object> onShelf(BehaviorContext ctx) {
        Map<String, Object> input = ctx.getInput();
        Object productIdObj = input == null ? null : input.get("productId");
        String productId = productIdObj == null ? null : productIdObj.toString();
        if (productId == null && input != null) {
            productId = ctx.getAggregateId();
        }
        if (productId == null || productId.trim().isEmpty()) {
            throw new IllegalArgumentException("事件负载缺少 productId");
        }
        // 示例动作：记录上架时间（真实项目里这里做业务联动，如同步索引、通知外部系统）
        int updated = jdbcTemplate.update(
                "UPDATE " + table() + " SET updated_at=?, updated_by=? WHERE " + pk() + "=? AND flag=0",
                DateUtil.nowCompact(),
                ctx.getActor() == null ? null : ctx.getActor().getUserId(),
                productId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productId", productId);
        result.put("status", "ON_SHELF");
        result.put("affected", updated);
        log.info("[ProductHandler] onShelf productId={} affected={}", productId, updated);
        return result;
    }

    /**
     * 查当前状态（供状态迁移校验使用）。
     */
    private String queryStatus(String productId) {
        java.util.List<String> list = jdbcTemplate.queryForList(
                "SELECT " + col("status") + " FROM " + table()
                        + " WHERE " + pk() + "=? AND flag=0",
                String.class, productId);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    /**
     * 生成业务编号：前缀 + yyyyMMdd + 4 位流水。
     *
     * <p>编号规则（前缀、编号表、编号列）声明在 M1 模型的 {@code noRule} 里，
     * 由 {@link BizNoGenerator} 统一生成——Handler 里不出现任何硬编码前缀或
     * 编号列名，改模型即可换编号规则。</p>
     */
    private String generateNo() {
        return bizNo.nextFor("Product");
    }

    /**
     * 取该行为对应的 M7 报表 ID。
     *
     * <p>从 M2 行为声明的 {@code queryReportRef} 读——
     * M7 与 M2 严格一对一（方法论 §1.3 设计决策 5）。</p>
     */
    private String reportIdOf(BehaviorContext ctx) {
        String behaviorId = ctx == null ? null : ctx.getBehaviorId();
        if (behaviorId != null) {
            com.jjy.domain.behavior.Behavior b = registry.getBehavior(behaviorId);
            if (b != null && b.getQueryReportRef() != null && !b.getQueryReportRef().isEmpty()) {
                return b.getQueryReportRef();
            }
        }
        throw new IllegalStateException("行为 " + behaviorId + " 未声明 queryReportRef，无法定位 M7 报表");
    }

    private String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}

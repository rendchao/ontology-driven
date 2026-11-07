package com.jjy.application.handler.generated.product;

import com.jjy.application.behavior.BehaviorContext;
import com.jjy.domain.behavior.BehaviorHandler;
import com.jjy.infrastructure.yaml.PhysicalMappingResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 删除商品（M2 模板生成 · behaviorForm=TEMPLATE · templateKind=DELETE）
 *
 * <p><b>本文件由 codegen 自动生成</b>，源头是 models/m2-behavior-model.yaml。
 * 重新生成时若本文件已被修改，会被保留（按内容指纹判定）。</p>
 *
 * <p>生成物遵循一条铁律：<b>不硬编码任何业务语义</b>——
 * 表名 / 主键列 / 列名运行时问 MM 映射模型；
 * 主键与可更新字段问 M1；查询口径问 M7；
 * 权限与规则由 dispatcher 按 M2/M5/M3 校验。
 * 这里的代码只负责「按模型声明的形状把数据写进去」。</p>
 *
 * <p><b>注意</b>：{@code @BehaviorHandler} 的 Target 是 METHOD，
 * 必须标在方法上，不能标在类上（标错位置编译报「注释不在语句上」）。</p>
 *
 * <pre>
 * id      : Product_Delete
 * owner   : Product
 * type    : COMMAND / USER_ACTION
 * kind    : DELETE
 * desc    : 按主键逻辑删除商品（flag 置 1）
 * pre     : []
 * post    : 
 * rules   : []
 * perms   : [PERM-PRODUCT-DELETE]
 * events  : []
 * subs    : []
 * </pre>
 *
 * generated-signature:1fd6613eae19f04f
 * @author jjy-base (generated)
 */
@Component
public class Product_DeleteHandler {

    /** 物理映射解析器：表名 / 主键列 / 列名全部来自 MM 模型 */
    @Autowired
    private PhysicalMappingResolver physicalMapping;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 删除商品 */
    @BehaviorHandler("Product_Delete")
    @Transactional
    public Map<String, Object> delete(BehaviorContext ctx) {
        Map<String, Object> dto = extractDto(ctx);
        String table = physicalMapping.tableName("Product");
        String pk = physicalMapping.pkColumn(table);
        String flagCol = physicalMapping.column(table, "flag");

        Object pkObj = dto.get("productId");
        if (pkObj == null || String.valueOf(pkObj).trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Product_Delete 缺少主键 productId");
        }
        String id = String.valueOf(pkObj);

        int updated = jdbcTemplate.update(
                "UPDATE " + table + " SET " + flagCol + " = 1"
                        + " WHERE " + pk + " = ? AND " + flagCol + " = 0",
                id);
        if (updated == 0) {
            throw new IllegalStateException(
                    "删除商品失败：记录不存在或已删除 (" + pk + "=" + id + ")");
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("productId", id);
        result.put("deleted", Boolean.TRUE);
        return result;
    }
    /** 取请求参数：兼容 dto 包装与顶层两种写法 */
    private Map<String, Object> extractDto(BehaviorContext ctx) {
        Map<String, Object> input = ctx.getInput();
        Object dtoObj = input == null ? null : input.get("dto");
        Map<String, Object> dto = dtoObj instanceof Map
                ? (Map<String, Object>) dtoObj : input;
        return dto == null ? new LinkedHashMap<String, Object>() : dto;
    }
}

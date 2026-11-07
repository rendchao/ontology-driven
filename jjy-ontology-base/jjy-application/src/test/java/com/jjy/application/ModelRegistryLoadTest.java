package com.jjy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jjy.domain.registry.ModelRegistry;

/**
 * 本体模型加载测试：验证模型能被正确加载进注册表，且跨模型引用自洽。
 *
 * <p>放在 application 模块，因为完整的 Spring 上下文（含事件机制的行为调用器）
 * 位于该模块之下。
 *
 * <p>数据源用真实的独立测试库 {@code jjy_ontology_base_test}：迁移脚本含 MySQL 专有的
 * COMMENT / ON UPDATE 语法，H2 的 MySQL 兼容模式无法执行（错误码 90045），
 * 因此集成测试直接跑 MySQL。测试库与业务库完全隔离。
 */
@SpringBootTest(classes = com.jjy.Application.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/jjy_ontology_base_test"
                        + "?useUnicode=true&characterEncoding=UTF-8"
                        + "&connectionCollation=utf8mb4_unicode_ci&useSSL=false"
                        + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.type=com.alibaba.druid.pool.DruidDataSource",
                "spring.datasource.username=${TEST_DB_USERNAME:root}",
                "spring.datasource.password=${TEST_DB_PASSWORD:root123}",
                "spring.sql.init.mode=never",
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration",
                "spring.flyway.baseline-on-migrate=true",
                "spring.flyway.baseline-version=0",
                "spring.flyway.validate-on-migrate=false",
                "jjy.seed-on-startup=false",
                "jwt.secret=test-secret-key-for-unit-tests-at-least-32-chars-long",
                "jwt.expiration-seconds=86400",
                "jwt.refresh-expiration-seconds=604800",
                "jwt.issuer=jjy-base-test"
        })
class ModelRegistryLoadTest {

    @Autowired
    private ModelRegistry registry;

    @Test
    @DisplayName("模型注册表非空")
    void registryNotEmpty() {
        assertNotNull(registry);
        assertFalse(registry.getAggregateNames().isEmpty(), "应加载到聚合根");
        assertFalse(registry.getBehaviors().isEmpty(), "应加载到行为");
        assertFalse(registry.getRules().isEmpty(), "应加载到规则");
        assertFalse(registry.getRoles().isEmpty(), "应加载到角色");
        assertFalse(registry.getPermissions().isEmpty(), "应加载到权限");
        assertFalse(registry.getFlows().isEmpty(), "应加载到流程");
        assertFalse(registry.getQueryReports().isEmpty(), "应加载到报表");
        assertFalse(registry.getScreens().isEmpty(), "应加载到界面");
        assertFalse(registry.getMappings().isEmpty(), "应加载到映射");
        assertNotNull(registry.getMenus(), "应加载到菜单树");
    }

    @Test
    @DisplayName("示例业务聚合被正确登记")
    void productAggregateRegistered() {
        assertTrue(registry.getAggregateNames().containsKey("AGG-PRODUCT-001"),
                "应包含 AGG-PRODUCT-001，实际: " + registry.getAggregateNames().keySet());
        assertEquals("商品", registry.getAggregateNames().get("AGG-PRODUCT-001"));
    }

    @Test
    @DisplayName("平台聚合（用户/角色/审计）被正确登记")
    void platformAggregatesRegistered() {
        assertTrue(registry.getAggregateNames().containsKey("AGG-USER-001"));
        assertTrue(registry.getAggregateNames().containsKey("AGG-ROLE-001"));
        assertTrue(registry.getAggregateNames().containsKey("AGG-AUDIT-LOG-001"));
    }

    @Test
    @DisplayName("行为与规则可按 id 检索")
    void behaviorsAndRulesRetrievable() {
        assertNotNull(registry.getBehavior("Product_Query"), "应能按 id 取到行为");
        assertNotNull(registry.getRule("RULE-PRODUCT-NAME-REQUIRED"), "应能按 id 取到规则");
        assertTrue(registry.hasBehavior("Product_SaveAsDraft"));
        assertTrue(registry.hasRule("RULE-PRODUCT-PRICE-POSITIVE"));
    }

    @Test
    @DisplayName("跨模型引用校验通过（模型自洽）")
    void referencesAreConsistent() {
        assertTrue(registry.validateReferences(),
                "跨模型引用校验应通过（详见 validate 错误日志）");
    }
}

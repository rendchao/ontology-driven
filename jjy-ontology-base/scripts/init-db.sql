-- 底座初始化脚本（示例）
--
-- 用法：
--   mysql -uroot -p < scripts/init-db.sql
--
-- 建库 + 建账号。密码请按实际环境修改（与 application.yml 的 DB_USERNAME/DB_PASSWORD 对应）。

CREATE DATABASE IF NOT EXISTS `jjy_ontology_base`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 账号（与 application.yml 默认值一致；生产请改密码并用环境变量注入）
CREATE USER IF NOT EXISTS 'jjy'@'%' IDENTIFIED BY 'jjy123456';
GRANT ALL PRIVILEGES ON `jjy_ontology_base`.* TO 'jjy'@'%';
FLUSH PRIVILEGES;

-- 表结构由应用启动时的 Flyway 迁移自动创建（V1__platform_schema.sql）
-- 本脚本只负责「库与账号存在」，不手工建表——表结构由模型生成，避免与模型漂移。

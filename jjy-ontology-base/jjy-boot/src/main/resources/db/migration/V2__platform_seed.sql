-- ═══════════════════════════════════════════════════════════════════════════
-- jjy 底座 —— 平台种子数据 (Platform Seed)
--
-- 只播种「底座自带」的最小可用数据：管理员账号 + 管理员角色 + 基础菜单。
-- 业务数据（字典、菜单、角色）由业务项目的模型与种子脚本提供。
-- ═══════════════════════════════════════════════════════════════════════════

SET NAMES utf8mb4;

-- 管理员角色（role_code=admin 时自动拥有全部权限）
INSERT INTO `t_role` (`role_id`, `role_code`, `role_name`, `role_type`, `permission_ids`, `status`, `description`, `created_by`, `created_at`, `updated_by`, `updated_at`, `flag`)
VALUES ('ROLE-ADMIN', 'admin', '系统管理员', 'PLATFORM', '', 'ACTIVE', '拥有全部权限', 'SYSTEM', NOW(3), 'SYSTEM', NOW(3), 0)
ON DUPLICATE KEY UPDATE `role_name` = VALUES(`role_name`), `updated_at` = NOW(3);

-- 管理员账号（password_hash = BCrypt("admin123")，已验证可登录）
INSERT INTO `t_user` (`user_id`, `username`, `password_hash`, `full_name`, `user_type`, `role_ids`, `phone`, `email`, `status`, `last_login_at`, `password_changed_at`, `failed_login_count`, `locked_until`, `created_by`, `created_at`, `updated_by`, `updated_at`, `flag`)
VALUES ('USER-ADMIN', 'admin', '$2a$10$c.kQLKoGyWX1GhHXuscaEODIGnH7cEb2K/.yQn324nIryVQ0EISAG', '系统管理员', 'PLATFORM', 'ROLE-ADMIN', '13800000000', 'admin@example.com', 'ACTIVE', NULL, NOW(3), 0, NULL, 'SYSTEM', NOW(3), 'SYSTEM', NOW(3), 0)
ON DUPLICATE KEY UPDATE `full_name` = VALUES(`full_name`), `updated_at` = NOW(3);

-- 基础菜单
INSERT INTO `t_menu` (`code`, `name`, `type`, `parent_id`, `path`, `icon`, `sort`, `permission`, `status`, `created_at`, `updated_at`, `created_by`, `flag`)
VALUES
  ('menu.workbench', '工作台', 'GROUP', NULL, '/workbench', 'dashboard', 10, NULL, 1, NOW(3), NOW(3), 'SYSTEM', 0),
  ('menu.system', '系统管理', 'GROUP', NULL, '/system', 'setting', 90, NULL, 1, NOW(3), NOW(3), 'SYSTEM', 0),
  ('menu.system.user', '用户管理', 'MENU', NULL, '/system/users', NULL, 91, 'system:user:list', 1, NOW(3), NOW(3), 'SYSTEM', 0),
  ('menu.system.role', '角色管理', 'MENU', NULL, '/system/roles', NULL, 92, 'system:role:list', 1, NOW(3), NOW(3), 'SYSTEM', 0),
  ('menu.system.dict', '数据字典', 'MENU', NULL, '/system/dictionaries', NULL, 93, 'system:dictionary:list', 1, NOW(3), NOW(3), 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `updated_at` = NOW(3);

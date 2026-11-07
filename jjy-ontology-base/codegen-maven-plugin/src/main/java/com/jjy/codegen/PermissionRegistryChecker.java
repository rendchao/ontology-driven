package com.jjy.codegen;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 对比 M5 声明的权限 vs M2/M5 内引用的权限集合。
 *
 * <p>方法论 §3.2.1 规定：{@code M2.behaviors[].requiredPermissions} 引用的是
 * <b>M5 的 permissionId</b>（如 {@code PERM-PRODUCT-ADD}），而不是自造字符串。</p>
 *
 * <p>双向闭合检查：</p>
 * <ul>
 *   <li>M2.requiredPermissions 引用了 M5 未声明的权限 → 漂移引用（运行期会 403）</li>
 *   <li>M5.roles[].permissions 授予了 M5 未声明的权限 → 漂移授权</li>
 *   <li>M5 声明了权限但没有任何行为要求、也没有角色授予 → 死权限</li>
 * </ul>
 *
 * <p>「死权限」只告警不阻断——权限可能是为 ENTITY 粒度预留的。</p>
 *
 * <p>用法：</p>
 * <pre>
 *   java com.jjy.codegen.PermissionRegistryChecker &lt;m2.yaml&gt; &lt;m5.yaml&gt;
 * </pre>
 */
public final class PermissionRegistryChecker {

    private PermissionRegistryChecker() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PermissionRegistryChecker <m2.yaml> <m5.yaml>");
            System.exit(2);
        }
        Path m2 = Paths.get(args[0]);
        Path m5 = Paths.get(args[1]);

        Set<String> declared = readM5PermissionIds(m5);
        Set<String> requiredByBehavior = readM2RequiredPermissions(m2);
        Set<String> grantedByRole = readM5RolePermissions(m5);

        // check 1: M2.requiredPermissions 必须被 M5 声明
        Set<String> strayRequired = new TreeSet<>(requiredByBehavior);
        strayRequired.removeAll(declared);

        // check 2: M5 角色授予的权限必须被 M5 声明
        Set<String> strayGranted = new TreeSet<>(grantedByRole);
        strayGranted.removeAll(declared);

        // check 3: 死权限（声明了但既没行为要求也没角色授予）
        Set<String> used = new LinkedHashSet<>(requiredByBehavior);
        used.addAll(grantedByRole);
        Set<String> dead = new TreeSet<>(declared);
        dead.removeAll(used);

        System.out.println("=== PermissionRegistry 检查（M2↔M5 权限引用闭合）===");
        System.out.println("M5 声明 permission 总数      : " + declared.size());
        System.out.println("M2 requiredPermissions 唯一值 : " + requiredByBehavior.size());
        System.out.println("M5 角色授予的唯一权限        : " + grantedByRole.size());
        System.out.println("  - M2 引用但 M5 未声明 (stray required) : " + strayRequired.size());
        System.out.println("  - M5 角色授予但未声明 (stray granted)  : " + strayGranted.size());
        System.out.println("  - 声明但无人使用 (dead, 仅告警)        : " + dead.size());

        if (!strayRequired.isEmpty()) {
            System.out.println("\n--- [stray required] M2.requiredPermissions 引用了 M5 未声明的权限 ---");
            strayRequired.forEach(p -> System.out.println("  " + p));
            System.out.println("  修复：把该 permissionId 补进 m5-actor-model.yaml 的 permissions[]，"
                    + "或修正 M2 里的拼写。");
        }
        if (!strayGranted.isEmpty()) {
            System.out.println("\n--- [stray granted] M5 角色授予了未声明的权限 ---");
            strayGranted.forEach(p -> System.out.println("  " + p));
        }
        if (!dead.isEmpty()) {
            System.out.println("\n--- [dead] M5 声明但无行为要求、也无角色授予 ---");
            dead.forEach(p -> System.out.println("  " + p));
        }

        // 死权限不阻断（可能是 ENTITY 粒度预留），仅引用漂移阻断
        boolean drift = !strayRequired.isEmpty() || !strayGranted.isEmpty();
        System.exit(drift ? 1 : 0);
    }

    /** M5 permissions[].permissionId */
    static Set<String> readM5PermissionIds(Path m5Yaml) throws IOException {
        Set<String> out = new TreeSet<>();
        Object root = load(m5Yaml);
        if (!(root instanceof Map)) {
            return out;
        }
        Object permsObj = ((Map<?, ?>) root).get("permissions");
        if (!(permsObj instanceof List)) {
            return out;
        }
        for (Object p : (List<?>) permsObj) {
            if (p instanceof Map) {
                Object id = ((Map<?, ?>) p).get("permissionId");
                if (id == null) {
                    id = ((Map<?, ?>) p).get("id");
                }
                if (id != null) {
                    out.add(id.toString());
                }
            }
        }
        return out;
    }

    /** M2 behaviors[].requiredPermissions[] 平铺 */
    static Set<String> readM2RequiredPermissions(Path m2Yaml) throws IOException {
        Set<String> out = new TreeSet<>();
        Object root = load(m2Yaml);
        if (!(root instanceof Map)) {
            return out;
        }
        Object behaviorsObj = ((Map<?, ?>) root).get("behaviors");
        if (!(behaviorsObj instanceof List)) {
            return out;
        }
        for (Object b : (List<?>) behaviorsObj) {
            if (!(b instanceof Map)) {
                continue;
            }
            Object arr = ((Map<?, ?>) b).get("requiredPermissions");
            if (arr instanceof List) {
                for (Object x : (List<?>) arr) {
                    if (x != null) {
                        out.add(x.toString());
                    }
                }
            }
        }
        return out;
    }

    /** M5 roles[].permissions[] 平铺 */
    static Set<String> readM5RolePermissions(Path m5Yaml) throws IOException {
        Set<String> out = new TreeSet<>();
        Object root = load(m5Yaml);
        if (!(root instanceof Map)) {
            return out;
        }
        Object rolesObj = ((Map<?, ?>) root).get("roles");
        if (!(rolesObj instanceof List)) {
            return out;
        }
        for (Object r : (List<?>) rolesObj) {
            if (!(r instanceof Map)) {
                continue;
            }
            Object arr = ((Map<?, ?>) r).get("permissions");
            if (arr instanceof List) {
                for (Object x : (List<?>) arr) {
                    if (x != null) {
                        out.add(x.toString());
                    }
                }
            }
        }
        return out;
    }

    private static Object load(Path yaml) throws IOException {
        try (InputStream in = Files.newInputStream(yaml)) {
            return new Yaml().load(in);
        }
    }
}

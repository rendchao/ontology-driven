package com.jjy.codegen;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 对比 MU yaml 菜单 vs 前端 Vue Router：
 *  1) router path 但 menu 未声明 → 未授权访问的死链
 *  2) 叶子 menu（有 screenRef）但 router 没有对应页面 → 菜单点了 404
 *  3) 叶子 menu 的 screenRef 指向 screens[] 里不存在的 screen → 悬空界面引用
 *
 * 关联键：menu.name vs router meta.title（两者都是中文菜单标题）。
 * 一级菜单（无 screenRef 字段）允许在 router 中无对应 path。
 *
 * 用法：
 *   java com.jjy.codegen.MenuRouteChecker
 *     <mu.yaml>  <router.js>
 */
public final class MenuRouteChecker {

    private MenuRouteChecker() {}

    static final Set<String> ANON_PATHS = new HashSet<>(Arrays.asList(
            "/login", "/select-tenant", "/403", "/"));

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: MenuRouteChecker <mu.yaml> <router.js>");
            System.exit(2);
        }
        Path mu = Paths.get(args[0]);
        Path routerJs = Paths.get(args[1]);

        // ── 解析 MU menus ──
        List<MenuNode> allMenus = readMenus(mu);
        List<MenuNode> leaves = new ArrayList<>();
        List<MenuNode> groups = new ArrayList<>();
        for (MenuNode m : allMenus) {
            if (m.screenRef != null && !m.screenRef.isEmpty()) leaves.add(m);
            else groups.add(m);
        }

        // ── 解析 MU screens（用于校验 screenRef 闭合）──
        Set<String> screenIds = readScreenIds(mu);

        // ── 解析 router ──
        List<RouteNode> routes = readRoutes(routerJs);
        List<RouteNode> businessRoutes = new ArrayList<>();
        for (RouteNode r : routes) {
            if (ANON_PATHS.contains(r.path) || r.path == null || r.path.isEmpty()) continue;
            if ("*".equals(r.path)) continue;
            businessRoutes.add(r);
        }

        // ── 关联：按 title/name 互查 ──
        Set<String> menuTitles = new TreeSet<>();
        for (MenuNode m : allMenus) menuTitles.add(m.name);
        Set<String> routeTitles = new TreeSet<>();
        for (RouteNode r : businessRoutes) routeTitles.add(r.title);

        // 叶子菜单 vs 所有 route 标题：双向
        Set<String> leafTitles = new TreeSet<>();
        for (MenuNode m : leaves) leafTitles.add(m.name);

        Set<String> leafMissingInRoute = new TreeSet<>(leafTitles);
        leafMissingInRoute.removeAll(routeTitles);
        // 只拿「业务页」标题比对：登录页等 anon 路由本就不在菜单里，不算漂移
        Set<String> routeMissingInMenu = new TreeSet<>(routeTitles);
        routeMissingInMenu.removeAll(menuTitles);

        System.out.println("=== MenuRoute 检查（MU menus ↔ Vue router）===");
        System.out.println("MU menu 总数（递归）        : " + allMenus.size());
        System.out.println("  - 叶子（有 screenRef）    : " + leaves.size());
        System.out.println("  - 分组（一级菜单/容器）   : " + groups.size());
        System.out.println("MU screen 总数              : " + screenIds.size());
        System.out.println("Router path 总数           : " + routes.size());
        System.out.println("  - 业务页（去 anon/*）    : " + businessRoutes.size());
        System.out.println("  - 叶子菜单无 router 页面 : " + leafMissingInRoute.size());
        System.out.println("  - 业务页无 menu 声明     : " + routeMissingInMenu.size());

        // ── 第 3 类：screenRef 悬空 ──
        Set<String> danglingScreenRef = new TreeSet<>();
        for (MenuNode m : leaves) {
            if (m.screenRef != null && !m.screenRef.isEmpty() && !screenIds.contains(m.screenRef)) {
                danglingScreenRef.add(m.menuId + "  " + m.name + "  screenRef=" + m.screenRef);
            }
        }
        System.out.println("  - screenRef 悬空         : " + danglingScreenRef.size());

        if (!leafMissingInRoute.isEmpty()) {
            System.out.println("\n--- [leaf menu 缺 router] 菜单声明了但前端没页面 ---");
            leafMissingInRoute.forEach(t -> {
                MenuNode m = findByName(leaves, t);
                System.out.println("  " + (m == null ? "" : m.menuId) + "  " + t
                        + (m != null && m.screenRef != null ? "  screenRef=" + m.screenRef : ""));
            });
        }
        if (!routeMissingInMenu.isEmpty()) {
            System.out.println("\n--- [route 缺 menu] 前端有页面但菜单没声明（直接输 URL 才到） ---");
            routeMissingInMenu.forEach(t -> {
                RouteNode r = findByTitle(businessRoutes, t);
                System.out.println("  " + (r == null ? "" : r.path) + "  " + t
                        + (r != null && r.permission != null ? "  permission=" + r.permission : ""));
            });
        }
        if (!danglingScreenRef.isEmpty()) {
            System.out.println("\n--- [screenRef 悬空] 菜单引用的界面在 screens[] 里没有定义 ---");
            danglingScreenRef.forEach(s -> System.out.println("  " + s));
            System.out.println("  已定义的 screenId：" + screenIds);
        }
        boolean drift = !leafMissingInRoute.isEmpty() || !routeMissingInMenu.isEmpty()
                || !danglingScreenRef.isEmpty();
        System.exit(drift ? 1 : 0);
    }

    /** 读取 MU screens[].screenId（用于校验菜单 screenRef 闭合） */
    @SuppressWarnings("unchecked")
    static Set<String> readScreenIds(Path muYaml) throws IOException {
        Set<String> out = new TreeSet<>();
        try (InputStream in = Files.newInputStream(muYaml)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return out;
            Object screens = ((Map<?, ?>) root).get("screens");
            if (!(screens instanceof List)) return out;
            for (Object s : (List<?>) screens) {
                if (s instanceof Map) {
                    Object id = ((Map<String, Object>) s).get("screenId");
                    if (id != null) out.add(id.toString());
                }
            }
        }
        return out;
    }

    static MenuNode findByName(List<MenuNode> list, String name) {
        for (MenuNode m : list) if (m.name.equals(name)) return m;
        return null;
    }
    static RouteNode findByTitle(List<RouteNode> list, String title) {
        for (RouteNode r : list) if (r.title.equals(title)) return r;
        return null;
    }

    // ════════ MU 解析 ════════

    static class MenuNode {
        String menuId;
        String name;
        String screenRef;
        MenuNode(String id, String n, String s) { menuId = id; name = n; screenRef = s; }
    }

    /** 递归读 application.menus[].*  含 children */
    @SuppressWarnings("unchecked")
    static List<MenuNode> readMenus(Path muYaml) throws IOException {
        List<MenuNode> out = new ArrayList<>();
        try (InputStream in = Files.newInputStream(muYaml)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return out;
            Object app = ((Map<?, ?>) root).get("application");
            if (!(app instanceof Map)) return out;
            Object menus = ((Map<?, ?>) app).get("menus");
            if (!(menus instanceof List)) return out;
            for (Object m : (List<?>) menus) {
                if (m instanceof Map) collectMenus((Map<String, Object>) m, out);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectMenus(Map<String, Object> node, List<MenuNode> out) {
        Object id = node.get("menuId");
        Object name = node.get("name");
        Object sr = node.get("screenRef");
        out.add(new MenuNode(
                id == null ? "" : id.toString(),
                name == null ? "" : name.toString(),
                sr == null ? null : sr.toString()));
        Object kids = node.get("children");
        if (kids instanceof List) {
            for (Object c : (List<?>) kids) {
                if (c instanceof Map) collectMenus((Map<String, Object>) c, out);
            }
        }
    }

    // ════════ Router 解析 ════════

    static class RouteNode {
        String path;     // 子 path（不含 / 前缀）
        String title;    // meta.title
        String permission;
        RouteNode(String p, String t, String perm) { path = p; title = t; permission = perm; }
    }

    /** 解析 router/index.js：
     *  匹配 { path: '...', name: '...', meta: { title: '...', permission: '...' } }
     *  匹配 path 里的 /xx-yy 形如子路由
     */
    static List<RouteNode> readRoutes(Path routerJs) throws IOException {
        List<RouteNode> out = new ArrayList<>();
        String src = new String(Files.readAllBytes(routerJs), java.nio.charset.StandardCharsets.UTF_8);
        // 简化：把多行 route 合并成单行后正则
        src = src.replaceAll("\\s*\\n\\s*", " ");
        // 匹配 { path: 'foo', name: 'Bar', component: ..., meta: { title: '...', permission: '...' } }
        // 也允许 path 在最前或 path 在 children 层级：此处粗匹配，children 子路由 path 不带 /
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\{\\s*path:\\s*'([^']+)'[^}]*?meta:\\s*\\{\\s*title:\\s*'([^']+)'(?:[^}]*?permission:\\s*'([^']*)')?"
        ).matcher(src);
        while (m.find()) {
            String path = m.group(1);
            String title = m.group(2);
            String perm = m.group(3);
            out.add(new RouteNode(path, title, perm));
        }
        return out;
    }
}

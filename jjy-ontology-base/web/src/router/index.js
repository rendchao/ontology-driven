/**
 * 前端路由（示例骨架）。
 *
 * <p>本文件是「MU UI 模型 ↔ 前端路由」对账的<b>代码端</b>：漂移守门员
 * （scripts/check-model-drift.ps1）会比对 models/mu-ui-model.yaml 里声明的
 * 菜单与本文件的路由条目，不一致即阻断提交。</p>
 *
 * <p>底座只提供最小骨架，业务工程按 MU 模型补齐页面与路由即可。
 * 路由条目格式须与 MenuRouteChecker 的解析规则一致：</p>
 *
 * <pre>
 * {
 *   path: '/xxx/list',              // 路由路径
 *   name: 'XxxList',                // 组件名
 *   component: () => import(...),   // 组件
 *   meta: { title: '页面标题', permission: 'xxx:list' }
 * }
 * </pre>
 *
 * <p>注意：meta.title 必须与 MU 模型里对应菜单的 name 一致——
 * 对账按标题互查，不是按 path。</p>
 */

import Vue from 'vue'
import VueRouter from 'vue-router'

Vue.use(VueRouter)

const routes = [
  {
    // 根路径没有实际页面，重定向到第一个业务入口。
    // 少了这一条，router-view 在 '/' 下渲染空白（App.vue 里只渲染 router-view）。
    path: '/',
    redirect: '/product/list'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/workbench/todo',
    name: 'MyTodos',
    component: () => import('../views/workbench/MyTodos.vue'),
    meta: { title: '我的待办' }
  },
  {
    path: '/product/list',
    name: 'ProductMaintain',
    component: () => import('../views/product/ProductMaintain.vue'),
    meta: { title: '商品档案', permission: 'product:list' }
  },
  {
    path: '/product/query',
    name: 'ProductQuery',
    component: () => import('../views/product/ProductQuery.vue'),
    meta: { title: '商品查询', permission: 'product:list' }
  }
]

/**
 * 路由实例。
 *
 * <p><b>必须导出 VueRouter 实例，不能只导出路由数组</b>——
 * main.js 的路由守卫要调 {@code router.beforeEach(...)}，
 * 数组上没有这个方法，页面会因「beforeEach is not a function」整个挂掉。</p>
 */
const router = new VueRouter({
  mode: 'history',
  routes
})

export default router

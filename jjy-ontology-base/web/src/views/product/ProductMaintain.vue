<template>
  <!--
    商品维护页 —— 模型驱动。

    表单字段、表格列、操作按钮全部来自 MU 模型的 screen 定义
    （FrmProductMaintain），前端不再硬编码任何字段与按钮。

    改 MU 模型 → 界面自动跟着变；改 M2 行为 → 按钮自动调新行为。
  -->
  <model-screen
    screen-id="FrmProductMaintain"
    query-behavior="Product_Query"
    save-behavior="Product_SaveAsDraft"
    pk-field="productId"
  />
</template>

<script>
/**
 * 商品维护页 —— 演示「模型驱动界面」的前端写法。
 *
 * <p><b>关键点</b>：本文件不含任何字段、按钮、行为名的硬编码，
 * 全部由 {@code ModelScreen} 组件按 MU 模型渲染。
 * 这与「前端不直接调业务 Service，只调 M2 行为 ID」是同一件事的两面——
 * 前端连「有哪些字段」都不自己说了算。</p>
 *
 * <p>业务工程新增界面时：</p>
 * <ol>
 *   <li>在 MU 模型加一个 screen（声明元素与动作）；</li>
 *   <li>本目录加一个 10 行的 Vue 文件引用 {@code <model-screen>}；</li>
 *   <li>在 router/index.js 加一条路由（标题须与 MU 菜单名一致）。</li>
 * </ol>
 */
import ModelScreen from '../../components/ModelScreen.vue'

export default {
  name: 'ProductMaintain',
  components: { ModelScreen }
}
</script>

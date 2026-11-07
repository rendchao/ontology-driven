<template>
  <div class="page">
    <el-card v-loading="loading">
      <div slot="header" class="card-header">
        <span class="card-title">{{ screen.name || screenId }}</span>
        <div class="toolbar">
          <!--
            操作按钮由 MU 的 actions[] 驱动：
            每个 action 的 behaviorRef 指向 M2 行为，点击即调该行为。
            增删按钮不用改前端代码——改模型即可。
          -->
          <el-button
            v-for="a in actions"
            :key="a.id"
            size="mini"
            :type="a.id === primaryActionId ? 'primary' : ''"
            :icon="actionIcon(a)"
            @click="onAction(a)"
          >{{ a.name }}</el-button>
        </div>
      </div>

      <!-- 表单字段由 MU 的 elements[].io=INPUT 驱动 -->
      <el-form ref="form" :model="form" :rules="rules" label-width="90px" class="form-grid">
        <el-form-item
          v-for="el in inputElements"
          :key="el.id"
          :label="el.label"
          :prop="el.id"
        >
          <!-- DICT 类型：选项来自 M1 dataDictionaries，经 /meta/dictionaries 获取 -->
          <el-select v-if="el.type === 'DICT'" v-model="form[el.id]" :placeholder="el.label" clearable style="width: 100%">
            <el-option
              v-for="d in dictOptions(el.dictionaryRef)"
              :key="d.code"
              :label="d.label"
              :value="d.code"
            />
          </el-select>

          <el-input-number
            v-else-if="el.type === 'DECIMAL' || el.type === 'INTEGER'"
            v-model="form[el.id]"
            :min="0"
            :precision="el.type === 'DECIMAL' ? 2 : 0"
            controls-position="right"
            style="width: 100%"
          />

          <!-- 只读字段：编号等由系统生成 -->
          <el-input
            v-else
            v-model="form[el.id]"
            :disabled="el.io !== 'INPUT' || el.readonly === true"
            :placeholder="el.label"
          />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="mt">
      <!-- 表格列由 MU 的 elements[]（非 INPUT）驱动 -->
      <el-table :data="rows" v-loading="loading" border size="small">
        <!--
          表格列由 MU 的 elements[].io=OUTPUT 驱动；
          列宽来自元素的 width 声明（后端归一化时已透出）。
        -->
        <el-table-column
          v-for="c in tableColumns"
          :key="c.id"
          :prop="c.id"
          :label="c.label"
          :width="c.width || undefined"
        >
          <template slot-scope="scope">
            <el-tag v-if="c.type === 'DICT'" :type="statusTagType(scope.row[c.id])" size="mini">
              {{ dictLabel(c.dictionaryRef, scope.row[c.id]) }}
            </el-tag>
            <span v-else>{{ scope.row[c.id] }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="140" fixed="right">
          <template slot-scope="scope">
            <el-button type="text" size="mini" @click="onEdit(scope.row)">编辑</el-button>
            <el-button
              v-for="a in rowActions"
              :key="a.id"
              type="text"
              size="mini"
              @click="onAction(a, scope.row)"
            >{{ a.name }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script>
/**
 * 模型驱动界面 —— 按 MU 模型的 screen 定义渲染表单与表格。
 *
 * <p><b>这是「模型是唯一语义源」在界面层的落地</b>：</p>
 * <ul>
 *   <li>表单字段：来自 {@code elements[].io=INPUT}（含 type / required / dictionaryRef）；</li>
 *   <li>表格列：来自 {@code elements[]} 中非 INPUT 的部分；</li>
 *   <li>操作按钮：来自 {@code actions[]}，每个动作调对应 {@code behaviorRef} 的 M2 行为。</li>
 * </ul>
 *
 * <p>此前这些全部硬编码在前端 Vue 模板里——改 MU 模型不生效，
 * 「模型说了不算」。现在改模型即改界面。</p>
 *
 * <p>用法：</p>
 * <pre>
 * &lt;model-screen screen-id="FrmProductMaintain"
 *                :query-behavior="Product_Query"
 *                :save-behavior="Product_SaveAsDraft"
 *                :pk-field="productId" /&gt;
 * </pre>
 */
export default {
  name: 'ModelScreen',
  props: {
    /** MU 里的 screenId */
    screenId: { type: String, required: true },
    /** 查询行为 ID（M2） */
    queryBehavior: { type: String, default: 'Product_Query' },
    /** 主保存行为 ID（M2）——点击表格行「编辑」后用它保存 */
    saveBehavior: { type: String, default: 'Product_SaveAsDraft' },
    /** 主键字段名 */
    pkField: { type: String, default: 'productId' }
  },
  data() {
    return {
      loading: false,
      screen: {},
      inputElements: [],
      tableColumns: [],
      actions: [],
      dictionaries: [],
      form: {},
      rules: {}
    }
  },
  computed: {
    /** 表格行上的操作：除主保存外的动作（如提交/下架/删除） */
    rowActions() {
      return this.actions.filter((a) => a.behaviorRef !== this.saveBehavior)
    },
    /** 头部按钮里的主操作（第一个非保存动作） */
    primaryActionId() {
      const first = this.actions.find((a) => a.behaviorRef !== this.saveBehavior)
      return first ? first.id : ''
    }
  },
  created() {
    this.loadScreen()
    this.loadDictionaries()
    this.loadRows()
  },
  methods: {
    /** 拉取 MU 声明的界面定义 */
    async loadScreen() {
      this.loading = true
      try {
        const s = await this.$http.get(`/meta/screens/${this.screenId}`)
        if (s) {
          this.screen = s
          this.inputElements = s.inputElements || []
          this.tableColumns = s.tableColumns || []
          this.actions = s.actions || []
          this.initForm()
        }
      } catch (e) {
        // 界面定义缺失时保持空表单，不阻塞页面
      } finally {
        this.loading = false
      }
    },
    async loadDictionaries() {
      try {
        const list = await this.$http.get('/meta/dictionaries')
        this.dictionaries = Array.isArray(list) ? list : []
      } catch (e) {
        this.dictionaries = []
      }
    },
    /** 按 MU 声明初始化表单与校验规则 */
    initForm() {
      const form = {}
      const rules = {}
      this.inputElements.forEach((el) => {
        form[el.id] = el.type === 'DECIMAL' || el.type === 'INTEGER' ? 0 : ''
        if (el.required) {
          rules[el.id] = [{
            required: true,
            message: `${el.label || el.id}必填`,
            trigger: el.type === 'DICT' || el.type === 'DECIMAL' ? 'change' : 'blur'
          }]
        }
      })
      this.form = form
      this.rules = rules
    },
    /** 字典选项：按 dictionaryRef 取 */
    dictOptions(dictionaryRef) {
      if (!dictionaryRef) return []
      return this.dictionaries.filter((d) => d.dictionaryId === dictionaryRef || d.typeCode === dictionaryRef)
    },
    dictLabel(dictionaryRef, code) {
      const opts = this.dictOptions(dictionaryRef)
      const hit = opts.find((d) => d.code === code)
      return hit ? hit.label : code
    },
    statusTagType(code) {
      if (code === 'ON_SHELF') return 'success'
      if (code === 'OFF_SHELF') return 'info'
      return 'warning'
    },
    async loadRows() {
      this.loading = true
      try {
        const data = await this.$http.post(`/behavior/${this.queryBehavior}`, { dto: {} })
        this.rows = (data && data.rows) || []
      } catch (e) {
        this.rows = []
      } finally {
        this.loading = false
      }
    },
    onEdit(row) {
      this.form = { ...this.form, ...row }
    },
    actionIcon(a) {
      if (a.id === 'saveDraft') return 'el-icon-document'
      if (a.id === 'submit') return 'el-icon-s-promotion'
      if (a.id === 'delete') return 'el-icon-delete'
      return 'el-icon-s-operation'
    },
    /**
     * 执行一个动作（MU 的 action → M2 行为）。
     *
     * <p>行为 ID 直接来自模型，前端不硬编码任何行为名。</p>
     */
    onAction(a, row) {
      // 提交类动作需要主键
      const needsPk = a.behaviorRef !== this.saveBehavior
      const payload = row ? { [this.pkField]: row[this.pkField] } : { ...this.form }
      if (needsPk && !payload[this.pkField]) {
        this.$message.warning('请先选择一条记录')
        return
      }
      if (a.behaviorRef === this.saveBehavior) {
        this.$refs.form.validate(async (valid) => {
          if (!valid) return
          await this.invoke(a.behaviorRef, payload)
        })
        return
      }
      this.invoke(a.behaviorRef, payload)
    },
    async invoke(behaviorId, dto) {
      try {
        await this.$http.post(`/behavior/${behaviorId}`, { dto })
        this.$message.success('操作成功')
        this.loadRows()
      } catch (e) {
        // 规则违反 / 状态机拒绝的中文提示由后端返回，http 拦截器已弹窗
      }
    }
  }
}
</script>

<style scoped>
.page {
  padding: 16px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-title {
  font-size: 16px;
  font-weight: 500;
}
.toolbar {
  display: flex;
  gap: 8px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  column-gap: 16px;
}
.mt {
  margin-top: 16px;
}
</style>

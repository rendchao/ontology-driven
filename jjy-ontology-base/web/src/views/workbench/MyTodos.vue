<template>
  <div class="page">
    <el-card>
      <div slot="header" class="card-header">
        <span class="card-title">我的待办</span>
        <el-button size="mini" icon="el-icon-refresh" @click="load">刷新</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" border size="small">
        <el-table-column prop="taskId" label="任务ID" width="180" />
        <el-table-column prop="title" label="事项" />
        <el-table-column prop="flowName" label="所属流程" width="160" />
        <el-table-column prop="createTime" label="到达时间" width="170" />
        <el-table-column label="操作" width="200" fixed="right">
          <template slot-scope="scope">
            <el-button type="text" size="mini" @click="onApprove(scope.row)">通过</el-button>
            <el-button type="text" size="mini" @click="onReject(scope.row)">驳回</el-button>
            <el-button type="text" size="mini" @click="onReturn(scope.row)">退回</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && rows.length === 0" description="暂无待办" />
    </el-card>
  </div>
</template>

<script>
/**
 * 我的待办 —— 工作台骨架。
 *
 * <p>底座提供审批中心的外壳与路由；<b>具体待办数据源由业务工程决定</b>
 * （可能是 M6 流程实例表，也可能是业务自建的任务表）。
 * 这里默认读流程实例，接入时替换 load() 的取数逻辑即可。</p>
 */
export default {
  name: 'MyTodos',
  data() {
    return {
      loading: false,
      rows: []
    }
  },
  created() {
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const data = await this.$http.get('/flow/instances/active')
        this.rows = (Array.isArray(data) ? data : []).map((it) => ({
          taskId: it.id,
          title: it.businessKey || it.id,
          flowName: it.flowId || '',
          createTime: it.createTime || ''
        }))
      } catch (e) {
        this.rows = []
      } finally {
        this.loading = false
      }
    },
    async onApprove(row) {
      try {
        await this.$http.post(`/flow/tasks/${row.taskId}/approve`, {})
        this.$message.success('已通过')
        this.load()
      } catch (e) {
        // 提示已统一处理
      }
    },
    async onReject(row) {
      try {
        await this.$http.post(`/flow/tasks/${row.taskId}/reject`, {})
        this.$message.success('已驳回')
        this.load()
      } catch (e) {
        // 提示已统一处理
      }
    },
    async onReturn(row) {
      try {
        await this.$http.post(`/flow/tasks/${row.taskId}/return`, {})
        this.$message.success('已退回')
        this.load()
      } catch (e) {
        // 提示已统一处理
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
</style>

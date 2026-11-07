<template>
  <div class="page">
    <el-card>
      <el-form :inline="true" :model="query" class="query-bar">
        <el-form-item label="商品名称">
          <el-input v-model="query.productName" placeholder="请输入" clearable @keyup.enter.native="onSearch" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="请选择" clearable style="width: 140px">
            <el-option v-for="d in statusOptions" :key="d.code" :label="d.label" :value="d.code" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="onSearch">查询</el-button>
          <el-button icon="el-icon-refresh" @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="mt">
      <el-table :data="rows" v-loading="loading" border size="small">
        <el-table-column prop="productNo" label="商品编号" width="160" />
        <el-table-column prop="productName" label="商品名称" />
        <el-table-column prop="category" label="分类" width="120" />
        <el-table-column prop="unit" label="单位" width="90" />
        <el-table-column prop="price" label="单价" width="110" />
        <el-table-column prop="status" label="状态" width="100">
          <template slot-scope="scope">
            <el-tag :type="statusTagType(scope.row.status)" size="mini">{{ statusLabel(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :current-page="query.page"
          :page-size="query.size"
          @current-change="onPageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script>
/**
 * 商品查询页 —— 演示 M7 查询报表与 M2 QUERY 行为的对接。
 *
 * <p>查询同样走行为入口（{@code POST /behavior/Product_Query}），
 * 而不是直接访问表——这样权限与数据范围由模型统一控制。</p>
 */
export default {
  name: 'ProductQuery',
  data() {
    return {
      loading: false,
      rows: [],
      total: 0,
      statusOptions: [],
      query: { productName: '', status: '', page: 1, size: 10 }
    }
  },
  created() {
    this.loadDictionaries()
    this.onSearch()
  },
  methods: {
    async loadDictionaries() {
      try {
        const list = await this.$http.get('/meta/dictionaries')
        const items = Array.isArray(list) ? list : []
        this.statusOptions = items.filter((d) => d.typeCode === 'product_status')
      } catch (e) {
        // 忽略
      }
    },
    async onSearch() {
      this.loading = true
      try {
        const data = await this.$http.post('/behavior/Product_Query', { dto: this.query })
        this.rows = (data && data.rows) || []
        this.total = (data && data.total) || 0
      } catch (e) {
        this.rows = []
        this.total = 0
      } finally {
        this.loading = false
      }
    },
    onReset() {
      this.query = { productName: '', status: '', page: 1, size: 10 }
      this.onSearch()
    },
    onPageChange(page) {
      this.query.page = page
      this.onSearch()
    },
    statusLabel(code) {
      const hit = this.statusOptions.find((d) => d.code === code)
      return hit ? hit.label : code
    },
    statusTagType(code) {
      if (code === 'ON_SHELF') return 'success'
      if (code === 'OFF_SHELF') return 'info'
      return 'warning'
    }
  }
}
</script>

<style scoped>
.page {
  padding: 16px;
}
.query-bar {
  margin-bottom: -12px;
}
.mt {
  margin-top: 16px;
}
.pager {
  margin-top: 12px;
  text-align: right;
}
</style>

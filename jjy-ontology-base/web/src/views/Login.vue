<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="login-title">{{ systemName }}</div>
      <el-form ref="form" :model="form" :rules="rules" @submit.native.prevent="onSubmit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" prefix-icon="el-icon-user" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            prefix-icon="el-icon-lock"
            show-password
            @keyup.enter.native="onSubmit"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" class="login-btn" @click="onSubmit">
            登录
          </el-button>
        </el-form-item>
      </el-form>
      <div class="login-tip">演示账号：admin / admin123</div>
    </el-card>
  </div>
</template>

<script>
import { setToken } from '../utils/auth'

export default {
  name: 'Login',
  data() {
    return {
      loading: false,
      systemName: '本体驱动业务系统',
      form: { username: '', password: '' },
      rules: {
        username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
        password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
      }
    }
  },
  methods: {
    async onSubmit() {
      this.$refs.form.validate(async (valid) => {
        if (!valid) return
        this.loading = true
        try {
          const data = await this.$http.post('/auth/login', this.form)
          setToken(data.accessToken)
          this.$message.success('登录成功')
          const redirect = this.$route.query.redirect || '/'
          this.$router.replace(redirect)
        } catch (e) {
          // 错误提示已由 http 拦截器统一处理
        } finally {
          this.loading = false
        }
      })
    }
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #4b3fe3 0%, #6f6fff 100%);
}
.login-card {
  width: 360px;
  padding: 8px 12px;
}
.login-title {
  font-size: 18px;
  font-weight: 600;
  text-align: center;
  margin-bottom: 20px;
  color: #171717;
}
.login-btn {
  width: 100%;
}
.login-tip {
  font-size: 12px;
  color: #909399;
  text-align: center;
}
</style>

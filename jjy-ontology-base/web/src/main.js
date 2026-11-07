import Vue from 'vue'
import ElementUI from 'element-ui'
import 'element-ui/lib/theme-chalk/index.css'

import App from './App.vue'
import router from './router'
import { setToken, getToken, clearToken } from './utils/auth'

Vue.use(ElementUI, { size: 'small' })
Vue.config.productionTip = false

// 全局请求拦截：注入 JWT
Vue.prototype.$http = require('./utils/http').default

// 路由守卫：未登录一律回登录页
router.beforeEach((to, from, next) => {
  const token = getToken()
  if (to.path === '/login') {
    next(token ? '/' : undefined)
    return
  }
  if (!token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  next()
})

new Vue({
  router,
  render: (h) => h(App),
  created() {
    // 启动时恢复登录态（token → 用户档案）
    if (getToken()) {
      this.$http.get('/auth/me').catch(() => clearToken())
    }
  }
}).$mount('#app')

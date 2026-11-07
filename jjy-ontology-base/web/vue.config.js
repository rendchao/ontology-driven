/**
 * Vue CLI 配置（Vue CLI 4 语法）。
 *
 * <p>开发期把 /api 代理到后端（默认 http://localhost:8080），
 * 前端代码里统一用相对路径 /api/v1，无需关心后端端口。</p>
 */
// ── Node 17+ 的 OpenSSL 3 兼容 ──
//
// Vue CLI 4 用的 webpack 4 用的是 OpenSSL 1.x 的哈希算法，
// 在 Node 17+（OpenSSL 3）下会报：
//     Error: error:0308010C:digital envelope routines::unsupported
// 这里在配置加载时补上 --openssl-legacy-provider，
// 让 npm run serve / npm run build 在新版 Node 上也能直接跑，
// 不必依赖调用方手动设 NODE_OPTIONS。
try {
  const crypto = require('crypto')
  const hasCipher = !!(crypto.getCiphers && crypto.getCiphers().includes('sha1-legacy'))
  const major = parseInt(process.versions.node.split('.')[0], 10)
  if (major >= 17 && !hasCipher) {
    process.env.NODE_OPTIONS = (process.env.NODE_OPTIONS || '') + ' --openssl-legacy-provider'
  }
} catch (e) {
  // 取不到 crypto 就算了——不影响非 Node 17+ 环境
}

module.exports = {
  transpileDependencies: [],
  lintOnSave: false,
  productionSourceMap: false,
  devServer: {
    port: 8081,
    proxy: {
      '/api': {
        target: process.env.VUE_APP_API_TARGET || 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
}

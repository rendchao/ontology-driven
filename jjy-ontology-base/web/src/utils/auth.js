/**
 * 令牌存储（localStorage）。
 *
 * <p>底座示例实现。生产环境若需更严格的 XSS 防护，可改为 httpOnly Cookie，
 * 但需后端相应调整 CORS 凭证策略。</p>
 */

const TOKEN_KEY = 'jjy_token'

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'
import { createLogger, defineConfig } from 'vite'

/**
 * 浏览器刷新时，聊天室那条 WebSocket 被直接掐断，而 Vite 的 ws 代理正往里写一帧，
 * 于是报 ECONNABORTED 并把整段堆栈打出来。原因就是刷新本身，不是服务端故障；
 * 可它每次刷新都出现，会把终端滚满。
 *
 * 这类断线会从两条路各报一次（ws proxy error 与 ws proxy socket error），都滤掉；
 * 其他代理错误照常打。
 */
const logger = createLogger()
const logError = logger.error
logger.error = (message, options) => {
  const wsProxy = message.includes('ws proxy error') || message.includes('ws proxy socket error')
  const aborted = message.includes('ECONNABORTED') || message.includes('ECONNRESET')
  if (wsProxy && aborted) {
    return
  }
  logError(message, options)
}

/**
 * 后端地址。默认 8080（见 application.yml 里的 `server.port`）。
 *
 * 可覆盖是为了“后端不在本机”；不要改成局域网 IP 就以为跨源解决了：浏览器仍按跨源处理，
 * cookie 和 WebSocket 都要另配。
 */
const backend = process.env.CODELOOM_BACKEND ?? 'http://localhost:8080'

export default defineConfig({
  customLogger: logger,
  plugins: [react(), tailwindcss()],

  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },

  server: {
    // ------------------------------------------------------------------
    // dev 代理：让浏览器从头到尾只看到同源。
    //
    // 认证靠会话 cookie：EventSource 发不了自定义请求头，WebSocket 握手同理。走代理后
    // 前端与 API 同源，cookie 自动带上，SameSite=Lax 的论证继续成立，后端不需要配 CORS。
    // 前端直连 8080 则要处理 CORS、`credentials: 'include'`、`SameSite=None`（会削弱
    // CSRF 论证）和 WebSocket 跨源许可；这四件事都是为了绕开一个本来不存在的问题。
    // ------------------------------------------------------------------
    proxy: {
      '/api': { target: backend },

      // 聊天室这条必须单独写，而且必须带 `ws: true`：少了它，握手会被当成普通 HTTP
      // 转发，服务端收到普通 GET，永远升不了级——表现为浏览器报“连接意外关闭”，
      // 服务端日志为空。
      '/ws/project-chat': { target: backend, ws: true },
    },
  },
})

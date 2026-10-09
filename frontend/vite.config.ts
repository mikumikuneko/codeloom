import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'

/**
 * 后端地址。默认 8080（见 application.yml 里的 `server.port`）。
 *
 * 做成可覆盖是为了"后端不在本机"这种情况 —— 但**不要**改成局域网 IP 就以为跨源解决了：
 * 那样浏览器看到的就是跨源请求，cookie 和 WebSocket 都得另配。
 */
const backend = process.env.CODELOOM_BACKEND ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react(), tailwindcss()],

  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },

  server: {
    // ------------------------------------------------------------------
    // dev 代理：让浏览器**从头到尾只看到同源**
    //
    // 这不是"图方便"，而是这个项目的认证方式决定的：凭据是会话 cookie，而
    //   · EventSource（SSE）发不了自定义请求头 —— 只能靠浏览器自动带 cookie
    //   · WebSocket 握手同理
    // 走代理的话前端和 API 就是同源，cookie 自动带上、SameSite=Lax 的论证继续成立、
    // **后端一行 CORS 都不用配**。
    //
    // 反过来（前端直连 8080）要处理的是：CORS + `credentials: 'include'` +
    // `SameSite=None`（那会同时削弱 CSRF 的论证）+ WebSocket 的跨源许可。
    // 四件事都是为了绕开一个本来不存在的问题。
    // ------------------------------------------------------------------
    proxy: {
      '/api': { target: backend },

      // 聊天室那条**必须单独写**、而且必须带 `ws: true`：
      // 少了它，握手请求会被当成普通 HTTP 转发，服务端收到的是个普通 GET，
      // 于是永远升不了级 —— 表现是浏览器报"连接意外关闭"，而服务端日志里什么都没有
      '/ws/project-chat': { target: backend, ws: true },
    },
  },
})

/**
 * 一个时刻，写给人看：`2026年10月7日 12:22`。
 *
 * <h2>为什么它是个共用的小函数，而不是各页各写一句</h2>
 * 现在有两处要显示"这张凭据什么时候作废"（协作弹窗里的邀请、别人点开的邀请页），
 * 而**同一个事实在两处必须长得一样** —— 各写一句 {@code toLocaleString} 的话，
 * 两边的精度、语言、甚至斜杠还是横杠都会慢慢分叉，而那种分叉没人会报 bug。
 *
 * <h2>为什么固定 zh-CN + 只到分钟</h2>
 * 界面全中文，跟着浏览器语言走只会让同一份数据在不同人屏幕上不一样。
 * 秒没用（人不会掐着秒看一张邀请什么时候失效），星期几也没用 ——
 * 这两档精度都会让一行本来能一眼读完的字变长。
 */
export function formatMoment(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
}

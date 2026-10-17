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

/**
 * 聊天记录里那行时间：今天只给时分，往前每一天/每一年各加一层限定。
 *
 * <p>不加限定的话，昨天 22:00 和今天 22:00 在屏幕上长得一模一样 ——
 * 那时候"时间"这个信息本身就是错的，比没有还坏。
 *
 * <p>和 {@link formatMoment} 分开：那个回答"这个时刻是哪天"，要完整日期；
 * 这个回答"这句话离上一句多久"，越近越短才有用。<b>但两者的格式只在这一个文件里</b> ——
 * 从前聊天面板自己又写了一套（M月D日 / 昨天 / 今年），改文案要记得改两处。
 */
export function formatChatTime(iso: string, now = new Date()): string {
  const at = new Date(iso)
  const clock = `${two(at.getHours())}:${two(at.getMinutes())}`
  if (isSameDay(at, now)) {
    return clock
  }

  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (isSameDay(at, yesterday)) {
    return `昨天 ${clock}`
  }

  const date = `${at.getMonth() + 1}月${at.getDate()}日`
  return at.getFullYear() === now.getFullYear()
    ? `${date} ${clock}`
    : `${at.getFullYear()}年${date} ${clock}`
}

function isSameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  )
}

function two(value: number): string {
  return String(value).padStart(2, '0')
}

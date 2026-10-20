/**
 * 一个时刻写给人看：`2026年10月7日 12:22`。
 *
 * 两处要显示"这张凭据什么时候作废"（协作弹窗里的邀请、别人点开的邀请页），同一
 * 事实在两处必须长得一样；各写一句 `toLocaleString` 会让精度、语言甚至分隔符
 * 慢慢分叉。固定 zh-CN 且只到分钟：界面全中文，跟着浏览器语言走会让同一份数据
 * 在不同屏幕上不一样；秒和星期只会让一行变长。
 */
export function formatMoment(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
}

/**
 * 聊天记录里的时间：今天只给时分，往前按昨天、今年、往年逐层加限定。
 *
 * 不加限定时，昨天 22:00 和今天 22:00 在屏幕上一样，那时"时间"这个信息本身
 * 就是错的。与 {@link formatMoment} 分开：那个回答"这个时刻是哪天"，要完整日期；
 * 这个回答"这句话离上一句多久"，越近越短才有用。两者的格式只在这一个文件里。
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

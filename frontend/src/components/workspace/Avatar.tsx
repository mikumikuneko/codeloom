import { personDot, type PersonSlot } from '@/lib/people'

/**
 * 头像：用户名的第一个字，底色是这个人的颜色。
 *
 * 字用深色：两块底色（青 `#5fa8b8`、紫 `#b87ba6`）都是中亮度，白字对比度只有
 * 2 出头，小字号下是糊的。
 */
export function Avatar({
  name,
  slot,
  className = 'size-8',
}: {
  name: string
  slot: PersonSlot
  /** 尺寸和字号；默认是聊天消息里的 32px */
  className?: string
}) {
  return (
    <span
      aria-hidden="true"
      title={name}
      className={`grid shrink-0 select-none place-items-center rounded-full text-xs font-medium text-[#14181c] ${personDot[slot]} ${className}`}
    >
      {firstChar(name)}
    </span>
  )
}

/** 第一个字。不用 `charAt(0)`：那是第一个 UTF-16 码元，emoji 会被切成半个。 */
function firstChar(name: string): string {
  return [...name.trim()][0] ?? '?'
}

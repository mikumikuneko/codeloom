import { Fragment } from 'react'

import { ComposerBox } from '@/components/workspace/ComposerBox'
import { LivePane } from '@/components/workspace/LivePane'
import type { ChatMessage, Project } from '@/lib/api'
import { personDot, slotsOf, type PersonSlot } from '@/lib/people'
import { useProjectChat } from '@/lib/useProjectChat'

/** 隔了这么久就值得标一次时间。挑 5 分钟是因为它正好是"一口气说完"和"过一会儿又说"的分界。 */
const TIME_GAP_MS = 5 * 60 * 1000

/**
 * 聊天室。
 *
 * <h2>它和左边的会话流**长得不一样**，这是有意的</h2>
 * 会话流里是 agent 在说话，这里是人。两件不同的事该有两种不同的样子：
 * <ul>
 *   <li><b>在这里</b>：头像贴边、气泡朝内 —— 自己的在右、对方的在左。
 *       那是聊天软件的通用语法，它承载的信息是"这句是谁说的"。</li>
 *   <li><b>那里</b>：满宽、不用气泡，一条消息就是满宽的一块文本（那条"谁的 agent"的彩色竖线已经删了）。</li>
 * </ul>
 *
 * <h2>头像就是名字，所以名字不再写一遍</h2>
 * 头像上那个字是用户名的头一个，底色是全站那套"谁是谁"的颜色
 * （见 {@code lib/people.ts}，和会话流里"谁的 agent"是同一对）——
 * 于是"青 = 那个人"整站成立，而名字写在旁边只是把同一件事说了两次。
 *
 * <h2>时间只在中途标，不在每条上标</h2>
 * 相邻两条隔了 {@link TIME_GAP_MS} 以上才在**后面那条的上方**插一条居中的时间。
 * 两个人对着说话的时候时间几乎没用，而每条都挂一个会把这一栏从"对话"读成"日志"。
 *
 * <p>写的时候按远近分层：今天只看时分，往前一天、一年各加一层限定。
 * 不加的话昨天 22:00 和今天 22:00 在屏幕上长得一模一样 —— 那时候"时间"这个信息
 * 本身就是错的，比没有还坏。
 */
export function ChatPanel({ project, meId }: { project: Project; meId: string | undefined }) {
  const { messages, disconnected, send } = useProjectChat(project.id)

  const slots = slotsOf(project.members.map((m) => m.id))
  const byId = new Map(project.members.map((m) => [m.id, m]))

  return (
    // 滚动、贴底、断线提示都在 LivePane 里；输入框在 ComposerBox 里 ——
    // **两层外壳都和会话流共用**，所以这里只回答一个问题：一条聊天消息长什么样
    <LivePane
      disconnected={disconnected}
      progress="连接断了，正在重连…"
      footer={
        // send 是同步的（往 socket 里塞一下），所以清空是**乐观**的：
        // 万一没发出去（连接断了），那句话就没了。这里接受这个代价 ——
        // 保留草稿会让"发送失败"看起来像"还没发"，而断线提示已经说清了为什么
        <ComposerBox placeholder="说点什么" disabled={disconnected} onSend={send} />
      }
    >
      <div className="space-y-3 px-4 py-2">
        {messages.length === 0 && (
          <p className="pt-6 text-sm leading-6 text-muted-foreground">
            这里是你和对方说话的地方，agent 看不见。
          </p>
        )}

        {messages.map((message, index) => (
          <Fragment key={message.id}>
            {needsTime(messages, index) && (
              <p className="text-center text-xs text-loom-faint">
                {timeLabel(message.createdAt)}
              </p>
            )}
            <Line
              message={message}
              mine={message.authorId === meId}
              authorName={byId.get(message.authorId)?.displayName ?? '某人'}
              slot={slots.get(message.authorId) ?? 'a'}
            />
          </Fragment>
        ))}
      </div>
    </LivePane>
  )
}

/**
 * 这一条上面要不要插时间。
 *
 * <p>判据是**和上一条的间隔**，不是"离现在多久" —— 后者会让翻上去的历史永远没有时间。
 * 第一条也标：一屏聊天记录没有起点时间，读起来是没有锚的。
 */
function needsTime(messages: ChatMessage[], index: number): boolean {
  if (index === 0) return true
  return (
    Date.parse(messages[index].createdAt) - Date.parse(messages[index - 1].createdAt) >= TIME_GAP_MS
  )
}

/** 今天只看时分；往前每退一档加一层限定，让"22:00"始终能定位到具体哪一天。 */
function timeLabel(iso: string): string {
  const at = new Date(iso)
  const now = new Date()
  const clock = `${two(at.getHours())}:${two(at.getMinutes())}`
  if (isSameDay(at, now)) return clock

  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (isSameDay(at, yesterday)) return `昨天 ${clock}`

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

/**
 * 一条消息：头像贴边、气泡朝里。自己的那一侧整个翻过来。
 *
 * <p>{@code flex-row-reverse} 不只是"把头像挪到右边"—— 它连主轴方向一起翻，
 * 所以两边用的是同一份 DOM 顺序和同一套间距，不需要为"自己的"再写一遍布局。
 */
function Line({
  message,
  mine,
  authorName,
  slot,
}: {
  message: ChatMessage
  mine: boolean
  authorName: string
  slot: PersonSlot
}) {
  return (
    <div className={`flex items-start gap-2 ${mine ? 'flex-row-reverse' : ''}`}>
      <Avatar name={authorName} slot={slot} />

      <div className="max-w-[70%] rounded-lg bg-secondary px-3 py-1.5">
        {/* 头像把名字接过去了，但那只是给眼睛的 —— 读屏软件读不到一个 aria-hidden 的圆 */}
        <span className="sr-only">{authorName}：</span>
        <p className="whitespace-pre-wrap break-words text-sm">{message.text}</p>
      </div>
    </div>
  )
}

/**
 * 头像：用户名的头一个字，底色是这个人的颜色。
 *
 * <p>上面那个字是**深色**的 —— 那两块底色（青 {@code #5fa8b8}、紫 {@code #b87ba6}）
 * 都是中亮度，白字压不住，对比度掉到 2 出头，小字号下基本是糊的。
 */
function Avatar({ name, slot }: { name: string; slot: PersonSlot }) {
  return (
    <span
      aria-hidden="true"
      title={name}
      className={`grid size-8 shrink-0 select-none place-items-center rounded-full text-xs font-medium text-[#14181c] ${personDot[slot]}`}
    >
      {firstChar(name)}
    </span>
  )
}

/** 第一个**字**。不写 `charAt(0)` —— 那是第一个 UTF-16 码元，emoji 会被切成半个。 */
function firstChar(name: string): string {
  return [...name.trim()][0] ?? '?'
}

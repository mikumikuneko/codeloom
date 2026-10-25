import { Fragment, useEffect } from 'react'

import { ComposerBox } from '@/components/workspace/ComposerBox'
import { LivePane } from '@/components/workspace/LivePane'
import type { ChatMessage, Project } from '@/lib/api'
import { Avatar } from '@/components/workspace/Avatar'
import { slotsOf, type PersonSlot } from '@/lib/people'
import { formatChatTime } from '@/lib/time'
import { useProjectChat } from '@/lib/useProjectChat'

/** 隔了这么久就值得标一次时间。5 分钟是一口气说完和过一会儿又说之间的分界。 */
const TIME_GAP_MS = 5 * 60 * 1000

/**
 * 聊天室。
 *
 * 它和左边的会话流长得不一样，这是有意的：会话流里 agent 在说话，这里是人。这里
 * 头像贴边、气泡朝内（自己的在右、对方的在左），表达"这句是谁说的"；会话流满宽、
 * 不用气泡。
 *
 * 头像就是名字，旁边不再写一遍：头像字是用户名的第一个字，底色是全站"谁是谁"的
 * 颜色（见 `lib/people.ts`）。
 *
 * 时间只在中途标：相邻两条超过 `TIME_GAP_MS` 才在后面那条上方插一条居中时间；
 * 每条都挂会把这一栏从对话读成日志。格式按远近分层（今天只给时分，往前加日期），
 * 否则昨天 22:00 和今天 22:00 长得一样，时间信息本身就是错的。
 */
export function ChatPanel({
  project,
  meId,
  nameOf,
  active = true,
  onMessages,
}: {
  project: Project
  meId: string | undefined
  /** 按 id 查名字，与工作区那份是同一个：两处各建 Map 会有两套"查不到时说什么" */
  nameOf: (id: string) => string | null
  /** 现在是否正看着聊天。不在看时连接仍然挂着，见下面那段 */
  active?: boolean
  /** 消息条数变了就通知；工作区靠它决定「聊天」标签上要不要点未读点 */
  onMessages?: (count: number, active: boolean) => void
}) {
  const { messages, disconnected, loaded, send } = useProjectChat(project.id)

  const slots = slotsOf(project.members.map((m) => m.id))

  // 离开这个标签页也不卸载它：连接要一直开着，否则"对方说话"只有回来那一刻
  // 才知道（见 WorkspacePage 的渲染处）。
  //
  // 等历史到了再报：那一批是打开页面时本来就在那儿的话，不该算未读。
  useEffect(() => {
    if (loaded) onMessages?.(messages.length, active)
  }, [messages.length, loaded, active, onMessages])

  return (
    // 滚动、贴底、断线提示在 LivePane 里；输入框在 ComposerBox 里。两层外壳都和
    // 会话流共用，所以这里只回答一个问题：一条聊天消息长什么样。
    <LivePane
      disconnected={disconnected}
      progress="连接断了，正在重连…"
      visible={active}
      footer={
        // send 同步（往 socket 里塞一下），所以清空是乐观的。还没连上时不会丢：
        // useProjectChat 排到 onopen 再补发。已连接但发送途中恰好断线仍会丢：
        // WebSocket 的 send 没有回执，这一层分辨不出来。保留草稿会让"发送失败"
        // 看起来像"还没发"，断线提示已经说清了原因。
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
                {formatChatTime(message.createdAt)}
              </p>
            )}
            <Line
              message={message}
              mine={message.authorId === meId}
              authorName={nameOf(message.authorId) ?? '某人'}
              slot={slots.get(message.authorId) ?? 'a'}
            />
          </Fragment>
        ))}
      </div>
    </LivePane>
  )
}

/**
 * 这一条上面是否要插时间。
 *
 * 判据是与上一条的间隔，不是"离现在多久"：后者会让翻上去的历史永远没有时间。
 * 第一条也标，否则一屏聊天记录没有起点时间。
 */
function needsTime(messages: ChatMessage[], index: number): boolean {
  if (index === 0) return true
  return (
    Date.parse(messages[index].createdAt) - Date.parse(messages[index - 1].createdAt) >= TIME_GAP_MS
  )
}

/**
 * 一条消息：头像贴边、气泡朝内。自己那一侧整个翻过来。
 *
 * `flex-row-reverse` 连主轴方向一起翻，两边因此共用同一份 DOM 顺序和间距，
 * 不需要为"自己的"另写布局。
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
        {/* 头像把名字接过去了，但那只给眼睛：读屏软件读不到 aria-hidden 的圆 */}
        <span className="sr-only">{authorName}：</span>
        <p className="whitespace-pre-wrap break-words text-sm">{message.text}</p>
      </div>
    </div>
  )
}

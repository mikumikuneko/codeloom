import { History, Trash2 } from 'lucide-react'
import { useState } from 'react'

import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import type { Session } from '@/lib/api'
import { personDot, type PersonSlot } from '@/lib/people'
import type { SessionState } from '@/lib/sessionState'

/**
 * 会话状态的显示名。查表 + 兜底，理由见 `WorkspacePage` 里那段。
 *
 * <p>键的类型是那份权威词表（`lib/sessionState`）—— **漏一个状态就编译不过**，
 * 而兜底那一支留着是因为线上来的字符串不保证认得出来。
 */
const STATE_LABELS: Record<SessionState, string> = {
  IDLE: '空闲',
  THINKING: '思考中',
  EXECUTING_TOOL: '执行工具',
  WAITING_USER: '等你说话',
  AWAITING_APPROVAL: '等你批准',
  FAILED: '失败',
}

/**
 * 会话历史。右栏顶部那个图标点开的。
 *
 * <h2>为什么它是弹窗，不是一直摊在右栏里</h2>
 * 右栏是"**现在**在发生什么"，而历史是"**以前**发生过什么"。把一份列表常驻在那儿，
 * 等于每次看流都要先跨过它 —— 而绝大多数时候你只关心眼前这一条。
 */
export function SessionHistoryDialog({
  sessions,
  slots,
  selectedId,
  onSelect,
  onOpen,
  onDiscard,
  title,
}: {
  sessions: Session[]
  slots: Map<string, PersonSlot>
  selectedId: string | null
  onSelect: (sessionId: string) => void
  /** 打开时补拉一次列表 —— 为什么见下面 `onOpenChange` 那段 */
  onOpen?: () => void
  /**
   * 丢弃一条会话（**真删**，见后端的 `discard`）。
   *
   * <p>界面上管它叫「**删除**」—— 那是给用户看的词，跟着另外两个确认框走
   *（删文件、删密钥）；代码里跟后端叫 `discard`。
   *
   * <p>**不给就没有这个入口** —— 对方那条列表就是这样：后端要驱动权，别人的会话点了
   * 必然失败，那就不该放一个永远按不动的按钮。
   *
   * <p>返回一句给人看的错误、或者 {@code null} 表示成功（同 {@link ConfirmDialog}）：
   * 后端「这条会话正在执行中」那条 409 就是从这里上来的。
   */
  onDiscard?: (sessionId: string) => Promise<string | null>
  title: string
}) {
  const [open, setOpen] = useState(false)
  /** 等着确认的那一条。为 null 就没在问 */
  const [discarding, setDiscarding] = useState<Session | null>(null)

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next)
        // 打开时**补拉一次**：这一列的标题是"我开口说的第一句"，而它由**服务端**从事件流里
        // 算（见 `SessionService.firstMessage`）—— 前端手上那份只在会话列表被拉回来时更新过。
        // 不补拉的话，刚说完第一句话的人打开这里看到的还是"还没有说话"，得刷新页面才对
        if (next) onOpen?.()
      }}
    >
      <DialogTrigger asChild>
        <Button
          variant="ghost"
          size="icon-sm"
          title={title}
          aria-label={title}
          className="text-muted-foreground"
        >
          <History className="size-4" />
        </Button>
      </DialogTrigger>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>选一条，右边就显示它的过程。</DialogDescription>
        </DialogHeader>

        <ul className="space-y-0.5">
          {sessions.map((session) => {
            const selected = session.id === selectedId
            return (
              // 底色和圆角挂在 li 上、不挂在按钮上：行尾多了一个删除键，
              // 两个按钮各自带底色的话，鼠标移到删除键上时整行的底色会断掉
              <li
                key={session.id}
                className={`flex items-center rounded hover:bg-accent ${
                  selected ? 'bg-selected' : ''
                }`}
              >
                <button
                  type="button"
                  onClick={() => {
                    onSelect(session.id)
                    setOpen(false)
                  }}
                  className="flex min-w-0 flex-1 items-baseline gap-2 px-2 py-2 text-left text-sm"
                >
                  <span
                    className={`inline-block size-1.5 shrink-0 self-center rounded-full ${
                      personDot[slots.get(session.ownerId) ?? 'a']
                    }`}
                  />
                  {/* 标题是**用户开口的第一句**：这个列表里全是同一个人的会话，
                      "谁在说"区分不了它们（见后端 SessionView 的类注释） */}
                  <span className="min-w-0 truncate">
                    {session.firstMessage ?? '还没有说话'}
                  </span>
                  {/* 状态和轮次靠右排：它们是次要信息，数着读比连着读清楚 */}
                  <span className="ml-auto shrink-0 text-xs text-loom-faint">
                    {STATE_LABELS[session.state] ?? session.state}
                    {session.turnIndex > 0 && ` · 第 ${session.turnIndex} 轮`}
                  </span>
                </button>
                {/* 平时是次要色，指上去才变红 —— 和右键菜单里那条一样：
                    破坏性动作该被认出来，但不该先喊（见 ContextMenu 的 danger） */}
                {onDiscard && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    title="删除这条会话"
                    aria-label="删除这条会话"
                    className="mr-1 shrink-0 text-muted-foreground hover:text-destructive"
                    onClick={() => setDiscarding(session)}
                  >
                    <Trash2 className="size-4" />
                  </Button>
                )}
              </li>
            )
          })}
        </ul>

        {discarding && onDiscard && (
          <ConfirmDialog
            title="删除后，这条会话找不回来"
            description="它记下的回滚位置也跟着没。代码不受影响。"
            confirmLabel="删除这条会话"
            onClose={() => setDiscarding(null)}
            onConfirm={async () => {
              const problem = await onDiscard(discarding.id)
              if (problem === null) {
                setDiscarding(null)
              }
              return problem
            }}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

import { History, Trash2 } from 'lucide-react'
import { useState } from 'react'

import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import type { Session } from '@/lib/api'
import { personDot, type PersonSlot } from '@/lib/people'
import type { SessionState } from '@/lib/sessionState'

/**
 * 会话状态的显示名。
 *
 * 键的类型是权威词表（`lib/sessionState`）：漏一个状态就编译不过；读取时的兜底
 * 保留，因为线上字符串不保证认得出来。
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
 * 弹窗而不是一直摊在右栏：右栏是"现在在发生什么"，历史是"以前发生过什么"。列表
 * 常驻会让每次看流都先跨过它，而绝大多数时候只关心眼前这一条。
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
  /** 打开时补拉一次列表；原因见下面 `onOpenChange` 那段 */
  onOpen?: () => void
  /**
   * 丢弃一条会话（真删，见后端的 `discard`）。
   *
   * 界面上叫「删除」：那是给用户看的词，跟着另外两个确认框走（删文件、删密钥）；
   * 代码里跟后端叫 `discard`。
   *
   * 不给就没有这个入口：对方那条列表就是这样。后端要驱动权，别人的会话点了必然
   * 失败，那就不该放一个永远按不动的按钮。
   *
   * 返回一句给人看的错误，或 null 表示成功（同 {@link ConfirmDialog}）：后端"这条
   * 会话正在执行中"的 409 就是从这里上来的。
   */
  onDiscard?: (sessionId: string) => Promise<string | null>
  title: string
}) {
  const [open, setOpen] = useState(false)
  /** 等着确认的那一条；为 null 表示没在问 */
  const [discarding, setDiscarding] = useState<Session | null>(null)

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next)
        // 打开时补拉一次：这一列的标题是"我开口说的第一句"，由服务端从事件流里算
        // （见 SessionService.firstMessage），前端手上那份只在会话列表被拉回来时
        // 更新过。不补拉的话，刚说完第一句话的人看到的还是"还没有说话"。
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
      {/* 没有说明文案：标题和列表已经说清这是什么；显式去掉 aria-describedby，
          免得 Radix 在控制台报缺少描述 */}
      <DialogContent className="sm:max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>

        <ul className="space-y-0.5">
          {sessions.map((session) => {
            const selected = session.id === selectedId
            return (
              // 底色和圆角挂在 li 上而不挂在按钮上：行尾多一个删除键，两个按钮
              // 各带底色的话，鼠标移到删除键上时整行底色会断。
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
                  {/* 标题是用户开口的第一句：列表里全是同一个人的会话，
                      "谁在说"区分不了它们 */}
                  <span className="min-w-0 truncate">
                    {session.firstMessage ?? '还没有说话'}
                  </span>
                  {/* 状态和轮次靠右排：次要信息，数着读比连着读清楚 */}
                  <span className="ml-auto shrink-0 text-xs text-loom-faint">
                    {STATE_LABELS[session.state] ?? session.state}
                    {session.turnIndex > 0 && ` · 第 ${session.turnIndex} 轮`}
                  </span>
                </button>
                {/* 平时次要色，指上去才变红，和右键菜单那条一样：
                    破坏性动作该被认出来，但不该先喊 */}
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

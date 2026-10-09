import { History } from 'lucide-react'
import { useState } from 'react'

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

/** 会话状态的显示名。查表 + 兜底，理由见 `WorkspacePage` 里那段。 */
const STATE_LABELS: Record<string, string> = {
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
  title,
}: {
  sessions: Session[]
  slots: Map<string, PersonSlot>
  selectedId: string | null
  onSelect: (sessionId: string) => void
  /** 打开时补拉一次列表 —— 为什么见下面 `onOpenChange` 那段 */
  onOpen?: () => void
  title: string
}) {
  const [open, setOpen] = useState(false)

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
              <li key={session.id}>
                <button
                  type="button"
                  onClick={() => {
                    onSelect(session.id)
                    setOpen(false)
                  }}
                  className={`flex w-full items-baseline gap-2 rounded px-2 py-2 text-left text-sm hover:bg-accent ${
                    selected ? 'bg-accent' : ''
                  }`}
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
              </li>
            )
          })}
        </ul>
      </DialogContent>
    </Dialog>
  )
}

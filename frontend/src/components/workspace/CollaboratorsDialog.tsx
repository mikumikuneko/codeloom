import { Link2 } from 'lucide-react'
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
import { Input } from '@/components/ui/input'
import { errorText, invitations, inviteUrl, type Invitation, type Project } from '@/lib/api'
import { personDot, slotsOf } from '@/lib/people'
import { formatMoment } from '@/lib/time'
import { useAsync } from '@/lib/useAsync'
import { useCopy } from '@/lib/useCopy'

/**
 * 协作：成员与邀请链接。
 *
 * 弹窗而不是页面：拉个人进来是偶尔做一次的事，而工作区是一直待着的地方。做成页面
 * 会让每次打开项目都跨过它，而它与看代码、和 agent 说话无关。
 *
 * 邀请链接必须列在这里：链接是看不见的凭据，发出去之后会忘，而它仍在有效期内。
 * 还没被接受的必须能看见并能撤销。
 */
export function CollaboratorsDialog({
  project,
  onOpen,
}: {
  project: Project
  /**
   * 弹窗打开时通知。
   *
   * 成员列表属于外面那份 `project`，它只在进页面时拉过一次；对方刚通过邀请链接
   * 进来时，这里仍会显示"还有一个名额 / 还没有发出去的链接"。打开弹窗正是"我想
   * 看看现在有谁"的时刻，重拉一次最自然。
   */
  onOpen?: () => void
}) {
  const [open, setOpen] = useState(false)

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next)
        if (next) onOpen?.()
      }}
    >
      <DialogTrigger asChild>
        <Button variant="ghost" size="sm" className="gap-1.5 text-muted-foreground">
          协作
        </Button>
      </DialogTrigger>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>协作</DialogTitle>
          <DialogDescription>
            一个项目最多两个人。邀请链接只能被用一次。
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-5 py-2">
          <Members project={project} />
          <Invites project={project} />
        </div>
      </DialogContent>
    </Dialog>
  )
}

function Members({ project }: { project: Project }) {
  // 名字前的点是全站同一对"谁是谁"的颜色；成员列表最不该缺席它。
  const slots = slotsOf(project.members.map((m) => m.id))
  return (
    <section className="space-y-2">
      <h3 className="text-xs text-muted-foreground">成员</h3>
      <ul className="space-y-1">
        {project.members.map((member) => (
          <li key={member.id} className="flex items-center gap-2 text-sm">
            <span
              className={`inline-block size-1.5 shrink-0 rounded-full ${personDot[slots.get(member.id) ?? 'a']}`}
            />
            {/* 只有用户名（显示用的那个）。账号与它常常一样，并排出现是两个相同的词，
                不是"信息更全" */}
            {member.displayName}
          </li>
        ))}
      </ul>
      {project.members.length < 2 && (
        // 说清"还差一个"，而不是只列现有人；后者要人自己数。
        <p className="text-xs text-loom-faint">还有一个名额。</p>
      )}
    </section>
  )
}

/**
 * 邀请链接：同时只有一张，所以这里永远只有一行。
 *
 * 刚生成的那张默认摊开（完整链接 + 复制），因为下一步就是复制它。但它和别的位置
 * 不是两种东西：摊开只是同一行的另一种状态。另起一个框会让同一个邀请被画两遍，
 * 撤销那一行时框不知道，链接还挂在屏幕上。
 *
 * 作废的行不显示：服务端只交出现在还能用的（见 `InvitationService.pending`）。
 */
function Invites({ project }: { project: Project }) {
  const { data: pending, reload } = useAsync(() => invitations.pending(project.id), [project.id])
  /** 刚生成那张的 token，用于摊开显示。存 token 而不是记录：刷新后记录是新对象，
   *  而这个判断只问"是不是同一张凭据" */
  const [openedToken, setOpenedToken] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const full = project.members.length >= 2

  async function issue() {
    setBusy(true)
    setError(null)
    try {
      const created = await invitations.issue(project.id)
      setOpenedToken(created.token)
      reload()
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="space-y-2 border-t border-border pt-4">
      <div className="flex items-center justify-between">
        <h3 className="text-xs text-muted-foreground">邀请</h3>
        {/* 最响的控件不该是它：它偶尔点一次，而实心主按钮表示"这一页就是要你按
            这个"；描边一档刚好 */}
        <Button variant="outline" size="sm" disabled={busy || full} onClick={() => void issue()}>
          {busy ? '正在生成…' : '生成链接'}
        </Button>
      </div>

      {/* 这条规矩得在按下去之前看见：它会作废别人手里那张 */}
      <p className="text-xs text-loom-faint">一个项目同时只有一张。再生成一张会让上一张失效。</p>

      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}

      {pending?.map((invitation) => (
        <LinkRow
          key={invitation.token}
          projectId={project.id}
          invitation={invitation}
          opened={invitation.token === openedToken}
          onToggle={() => setOpenedToken(invitation.token === openedToken ? null : invitation.token)}
          onChanged={reload}
        />
      ))}

      {pending?.length === 0 && (
        <p className="text-xs text-loom-faint">
          {full ? '项目已经满了' : '还没有发出去的链接'}
        </p>
      )}
    </section>
  )
}

/**
 * 那一行：一条链接，两种状态。
 *
 * 摊开时把完整链接摆在眼前（下一步就是复制）；收起时只剩失效时间和撤销。收起
 * 不等于作废：那一行还在，失效时间还在，链接就还活着。
 *
 * 所以收起之后必须留一条回去的路（"显示链接"）。少了它，想再抄一次就只剩"再生成
 * 一张"，而那会作废别人手里那张：为了少占两行而让唯一的回头路是破坏性的，代价
 * 不对等。
 */
function LinkRow({
  projectId,
  invitation,
  opened,
  onToggle,
  onChanged,
}: {
  projectId: string
  invitation: Invitation
  opened: boolean
  /** 摊开 ⇄ 收起。两种状态都要能到，见上面那段 */
  onToggle: () => void
  onChanged: () => void
}) {
  const [busy, setBusy] = useState(false)
  // 与代码块那个复制按钮共用反馈逻辑：失败不假装成功，成功 1.2 秒后收回。
  const [copied, copy] = useCopy()
  const url = inviteUrl(invitation.token)

  async function revoke() {
    setBusy(true)
    try {
      await invitations.revoke(projectId, invitation.token)
      onChanged()
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-2">
      {opened && (
        // 高度跟输入框对齐（h-8）：一行里两个控件差 4px，看着就是没对齐。
        <div className="flex gap-2">
          <Input
            readOnly
            value={url}
            aria-label="邀请链接"
            onFocus={(e) => e.currentTarget.select()}
            className="font-mono text-xs"
          />
          <Button size="sm" className="h-8 shrink-0" onClick={() => void copy(url)}>
            {copied ? '已复制' : '复制'}
          </Button>
        </div>
      )}

      <div className="flex items-center gap-2">
        {/* 活着的凭据要有看得见的锚点：一行孤零零的日期读不出"这是一条链接" */}
        <Link2 aria-hidden className="size-3.5 shrink-0 text-loom-faint" />
        <p className="text-xs text-loom-faint">{expiry(invitation.expiresAt)}</p>
        <div className="ml-auto flex items-center gap-1">
          {/* 两个动作同一种画法、同一种安静程度，谁也不比谁响。撤销的危险交给
              hover 才说；平时就红等于一直喊（同项目列表里「退出」的写法） */}
          <button
            type="button"
            onClick={onToggle}
            className="rounded px-2 py-1 text-xs text-loom-faint transition-colors hover:text-foreground"
          >
            {opened ? '收起' : '显示链接'}
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => void revoke()}
            className="rounded px-2 py-1 text-xs text-loom-faint transition-colors hover:text-destructive disabled:opacity-50"
          >
            撤销
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * 什么时候失效。
 *
 * 不写"24 小时后"这种相对说法：有效期由服务端定（见 `ProjectInvitation`），抄一个
 * 数到界面上，改后端就会对不上。这里显示服务端给的那个时刻。
 *
 * 时刻的写法在 {@link formatMoment} 里：邀请页也要显示同一个时刻，两处必须一样。
 */
function expiry(iso: string): string {
  return `${formatMoment(iso)} 前有效`
}

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
import { ApiError, invitations, inviteUrl, type Invitation, type Project } from '@/lib/api'
import { personDot, slotsOf } from '@/lib/people'
import { formatMoment } from '@/lib/time'
import { useAsync } from '@/lib/useAsync'

/**
 * 协作：成员 + 邀请链接。
 *
 * <h2>为什么它是个弹窗，而不是一个页面</h2>
 * 因为"拉个人进来"是**偶尔做一次**的事，而工作区是**一直待着**的地方。
 * 把它做成一个页面，就意味着每次打开项目都要跨过它 —— 而它和"看代码、和 agent 说话"
 * 没有一点关系。弹窗让它随叫随到，同时完全不占地方。
 *
 * <h2>为什么邀请链接必须列在这里</h2>
 * 链接是**看不见的凭据**：发出去之后你会忘了发过，而它还在有效期内。
 * 所以"还没被接受的"必须能看见，并且能撤销 —— 这是这个弹窗存在的一半理由。
 */
export function CollaboratorsDialog({ project }: { project: Project }) {
  const [open, setOpen] = useState(false)

  return (
    <Dialog open={open} onOpenChange={setOpen}>
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
  // 名字前面的点是全站同一对"谁是谁"的颜色 —— 成员列表是它最不该缺席的地方
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
            {/* 只有**用户名**（显示用的那个）。从前后面还跟一个账号，而注册时两者常常一样，
                于是同一行里出现两个一模一样的词 —— 那不是"信息更全"，那是噪音 */}
            {member.displayName}
          </li>
        ))}
      </ul>
      {project.members.length < 2 && (
        // 说清"还差一个"，而不是只列出现有的人 —— 后者要靠人自己数
        <p className="text-xs text-loom-faint">还有一个名额。</p>
      )}
    </section>
  )
}

/**
 * 邀请链接：**同时只有一张**，所以这里永远只有一行。
 *
 * <h2>为什么"刚生成的那张"要点开显示</h2>
 * 生成之后下一步就是复制它，所以那一行默认**摊开**（完整链接 + 复制）。
 * 但它和别的位置**不是两种东西** —— 摊开的是同一行的另一种状态：
 * 从前这里是个单独的框，于是同一个邀请被画了两遍（一个框、一行），
 * 撤销的时候撤销的是那一行，框不知道，**链接还挂在屏幕上**。
 *
 * <h2>作废的行哪去了</h2>
 * 服务端只交出现在还能用的（见 {@code InvitationService.pending}）。曾经这里
 * 把用过的、撤过的、过期的一行行列出来 —— 发一次攒一行"已撤销"，
 * 而真正要看的凭据被淹在里面。
 */
function Invites({ project }: { project: Project }) {
  const { data: pending, reload } = useAsync(() => invitations.pending(project.id), [project.id])
  /** 刚生成那张的 token —— 摊开显示它。**存 token 而不是存那条记录**：
   *  列表刷新之后那条记录是新对象，而这个判断只问"是不是同一张凭据" */
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
      setError(e instanceof ApiError ? e.message : '连不上服务器')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="space-y-2 border-t border-border pt-4">
      <div className="flex items-center justify-between">
        <h3 className="text-xs text-muted-foreground">邀请</h3>
        {/* 这个弹窗里最响的控件不该是它：它是个偶尔点一次的动作，
            而实心主按钮是"这一页就是要你按这个"的意思。描边一档刚好 */}
        <Button variant="outline" size="sm" disabled={busy || full} onClick={() => void issue()}>
          {busy ? '正在生成…' : '生成链接'}
        </Button>
      </div>

      {/* 这条规矩得在**按下去之前**看见：它会作废别人手里那张 */}
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
 * <p>摊开时把完整链接摆在眼前（下一步就是复制它）；收起时只剩"什么时候失效"和
 * 一个撤销 —— 而**"收起"不等于作废**：那一行还在，失效时间还在，链接就还活着。
 *
 * <p>所以收起之后**必须留一条回去的路**（"显示链接"）。少它的后果是具体的：
 * 想再抄一次那条链接，就只剩"再生成一张"这一条路 —— 而那会把别人手里那张作废掉。
 * 一个为了少占两行而设的开关，代价不该是"唯一能回去的动作是破坏性的"。
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
  /** 摊开 ⇄ 收起。**两种状态都要能到**，见上面那段 */
  onToggle: () => void
  onChanged: () => void
}) {
  const [copied, setCopied] = useState(false)
  const [busy, setBusy] = useState(false)
  const url = inviteUrl(invitation.token)

  async function copy() {
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
    } catch {
      // 剪贴板 API 在非安全上下文（http 的局域网地址）里会被拒。
      // 这时候**不假装成功** —— 链接就在旁边的输入框里，手动选中也行
      setCopied(false)
    }
  }

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
        // 高度跟输入框对齐（`h-8`）：一行里两个控件差 4px，看着就是"没对齐"，
        // 而说不清哪儿不对
        <div className="flex gap-2">
          <Input
            readOnly
            value={url}
            aria-label="邀请链接"
            onFocus={(e) => e.currentTarget.select()}
            className="font-mono text-xs"
          />
          <Button size="sm" className="h-8 shrink-0" onClick={() => void copy()}>
            {copied ? '已复制' : '复制'}
          </Button>
        </div>
      )}

      <div className="flex items-center gap-2">
        {/* 一张活着的凭据要有个看得见的锚点：一行孤零零的日期读不出"这是一条链接" */}
        <Link2 aria-hidden className="size-3.5 shrink-0 text-loom-faint" />
        <p className="text-xs text-loom-faint">{expiry(invitation.expiresAt)}</p>
        <div className="ml-auto flex items-center gap-1">
          {/* 两个动作同一种画法、同一种安静程度：它们是这一行的两个注脚，
              谁也不比谁响。撤销的"危险"交给 hover 才说 ——
              平时就红等于一直在喊（同项目列表里「退出」的那套） */}
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
 * <p>不写"24 小时后"那种相对说法：有效期是**服务端**定的（见 {@code ProjectInvitation}），
 * 抄一个数到界面上，改了后端就会对不上。这里显示的是服务端给的那个时刻。
 *
 * <p>时刻的写法本身在 {@link formatMoment} 里：邀请页那边也要显示同一个时刻，
 * 两处必须长得一样。
 */
function expiry(iso: string): string {
  return `${formatMoment(iso)} 失效`
}

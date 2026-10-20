import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { errorText, invitations } from '@/lib/api'
import { formatMoment } from '@/lib/time'
import { useAsync } from '@/lib/useAsync'
import { useAuth } from '@/stores/auth'

/**
 * 别人发来的邀请链接。
 *
 * 这一页刻意在登录关卡外面：点开链接的人多半还没注册，直接扔到登录页只会看到
 * "请登录"，不知道自己被邀请去哪、该不该注册。正确顺序是先让他看见"某某邀请你
 * 加入某某项目"，再决定要不要注册；所以预览不需要登录（后端同样），只有"加入"才要。
 *
 * 没有卡片：这一页只有几行字和一个动作，套盒子会把注意力引到容器上。与登录页
 * 共用的是"居中一列、产品名在最上面"的骨架。
 *
 * 主角是那一句话，所以用标题的体量："X 邀请你加入 Y"是这一页的全部信息，其余
 * （有效期、身份、出口、说明）都是注脚。
 *
 * 「换个账号」是控件不是一句话：把项目加到错的账号头上是这一页唯一会出的事故，
 * 且没有撤销入口，所以出口贴着身份行、是一个真按钮。
 *
 * 三种人三种主角（`preview.viewer` 说的）：还没加入 → 邀请是主角，动作是「加入
 * 项目」；已在这个项目里 → 主角是"你已经在 X 里了"，动作是「打开项目」；链接是
 * 自己发的 → 主角是"这是你自己生成的链接"，他想知道这张链接还能不能用。
 *
 * 提前到预览里说，发起人点自己的链接时就不会再把要发给对方的凭据作废。见
 * `docs/decisions/architecture/2026-09-29-invite-links-not-username-invites.md`。
 */
export function InvitePage() {
  const { token = '' } = useParams()
  const navigate = useNavigate()
  const status = useAuth((s) => s.status)
  const load = useAuth((s) => s.load)
  const user = useAuth((s) => s.user)
  const logout = useAuth((s) => s.logout)

  // 预览 = 一次标准的"拉一次"（加载 / 出错 / 取消），见 useAsync。
  const { data: preview, error: loadError, loading } = useAsync(
    () => invitations.preview(token),
    [token],
  )
  /** "加入"那一下的失败。和读取失败分开：一个还没拿到内容，一个已经有内容了 */
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // 这一页可能在登录关卡外面，所以"我是谁"要自己问一次。
  useEffect(() => {
    if (status === 'unknown') void load()
  }, [status, load])

  async function accept() {
    setBusy(true)
    setError(null)
    try {
      const project = await invitations.accept(token)
      navigate(`/projects/${project.id}`, { replace: true })
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  /**
   * 换个账号：先登出，再带着"要回到这一页"去登录页。
   *
   * 与匿名那条路用同一套约定（把来源记在 state 里，见 `RequireAuth`），登完回到
   * 这一页，链接不会白点。
   */
  async function switchAccount() {
    await logout()
    navigate('/login', { state: { from: `/invite/${token}` } })
  }

  const canAccept = preview?.usable === true
  /** 拿着链接的人是谁，服务端说了算（它看得见 cookie），见类注释 */
  const viewer = preview?.viewer ?? 'GUEST'
  /** 已经在项目里的人（包括发链接的人自己）：这一页对他没有"加入"可做 */
  const inside = viewer !== 'GUEST'

  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4">
      <div className="w-full max-w-sm space-y-10">
        {loading ? (
          // 读取中也要有产品名：来的人可能从没见过 codeloom，
          // 一屏只有"正在读取…"连自己点进了什么都不知道。
          <div className="space-y-6">
            <h1 className="text-sm font-semibold tracking-tight">codeloom</h1>
            <p className="text-sm text-muted-foreground">正在读取邀请…</p>
          </div>
        ) : preview === null ? (
          // 链接本身读不到：产品名还是要露，不然这一屏只剩一句报错。
          <div className="space-y-6">
            <h1 className="text-sm font-semibold tracking-tight">codeloom</h1>
            <p role="alert" className="text-sm text-destructive">
              {loadError ?? error ?? '连不上服务器'}
            </p>
          </div>
        ) : (
          <>
            <div className="space-y-6">
              {/* 小小的产品名：只回答"这是什么东西"，不抢下面那句话 */}
              <h1 className="text-sm font-semibold tracking-tight">codeloom</h1>

              <div className="space-y-3">
                <p className="text-xl font-medium leading-8 tracking-tight">
                  {viewer === 'INVITER' ? (
                    // 「为……生成」而不是「发给……」：后者把项目名摆成收件人，
                    // 而项目名的角色是"为哪个项目"。用"生成"还能和按钮上的
                    // 「生成链接」对上。
                    <>
                      <span className="text-muted-foreground">这是你自己为 </span>
                      <span className="text-foreground">{preview.projectName}</span>
                      <span className="text-muted-foreground"> 生成的链接</span>
                    </>
                  ) : viewer === 'MEMBER' ? (
                    <>
                      <span className="text-muted-foreground">你已经在 </span>
                      <span className="text-foreground">{preview.projectName}</span>
                      <span className="text-muted-foreground"> 里了</span>
                    </>
                  ) : (
                    <>
                      <span className="text-foreground">{preview.invitedBy ?? '项目成员'}</span>
                      <span className="text-muted-foreground"> 邀请你加入 </span>
                      <span className="text-foreground">{preview.projectName}</span>
                    </>
                  )}
                </p>

                {!canAccept ? (
                  preview.reason && (
                    <p role="alert" className="text-sm text-destructive">
                      {preview.reason}
                    </p>
                  )
                ) : viewer === 'MEMBER' ? null : (
                  // "什么时候作废"是"我该不该现在处理它"的直接答案。写时刻而不是
                  // "24 小时内"：有效期是服务端定的，抄一个数会对不上。
                  //
                  // 已经在内、且链接不是他发的人看不到这一行：这不是他的事。
                  <p className="text-xs text-loom-faint">
                    {formatMoment(preview.expiresAt)} 前有效
                  </p>
                )}

                {error && (
                  <p role="alert" className="text-sm text-destructive">
                    {error}
                  </p>
                )}
              </div>
            </div>

            {/* 已经在项目里的人：这一页对他只剩"进去"这件事 */}
            {canAccept && inside && (
              <div className="space-y-3">
                <Button
                  className="w-full"
                  onClick={() => navigate(`/projects/${preview.projectId}`)}
                >
                  打开项目
                </Button>
                {viewer === 'INVITER' && (
                  // 发起人心里那个问题只有一个：我这张链接废了吗。
                  <p className="text-xs text-loom-faint">
                    你已经在里面了 —— 这张链接还能用，发给对方就行。
                  </p>
                )}
              </div>
            )}

            {canAccept && !inside && status === 'anonymous' && (
              <div className="space-y-4">
                <p className="text-sm text-muted-foreground">
                  需要一个账号才能加入。注册或登录之后会回到这一页。
                </p>
                {/* 把"本来要去哪"记在 state 里，和 RequireAuth 同一套约定，
                    登录完成会回到 /invite/TOKEN */}
                <Button
                  className="w-full"
                  onClick={() => navigate('/login', { state: { from: `/invite/${token}` } })}
                >
                  去注册 / 登录
                </Button>
              </div>
            )}

            {canAccept && !inside && status === 'authenticated' && (
              <div className="space-y-4">
                {/* 身份行和出口同一行：加错人是唯一会出的事故，出口得是看得见的控件 */}
                <div className="flex items-center justify-between gap-3">
                  <p className="text-sm text-muted-foreground">
                    会以 <span className="text-foreground">{user?.displayName}</span> 的身份加入。
                  </p>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="shrink-0 text-muted-foreground"
                    onClick={() => void switchAccount()}
                  >
                    换个账号
                  </Button>
                </div>

                <div className="space-y-3">
                  <Button className="w-full" disabled={busy} onClick={() => void accept()}>
                    {busy ? '正在加入…' : '加入项目'}
                  </Button>
                  {/* 按钮是这一页唯一要做的事，下面要说清按下去之后会怎样 */}
                  <p className="text-xs text-loom-faint">加入之后直接打开这个项目。</p>
                </div>
              </div>
            )}

            {canAccept && !inside && status === 'unknown' && (
              <p className="text-sm text-muted-foreground">正在读取登录状态…</p>
            )}
          </>
        )}
      </div>
    </div>
  )
}

import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { ApiError, invitations, type InvitationPreview } from '@/lib/api'
import { formatMoment } from '@/lib/time'
import { useAuth } from '@/stores/auth'

/**
 * 别人发来的邀请链接。
 *
 * <h2>这一页刻意在登录关卡**外面**</h2>
 * 点开链接的人多半还没注册。把他直接扔到登录页，他只会看到"请登录"，
 * 而不知道自己被邀请去哪、该不该费劲注册 —— 那是这件事最差的顺序。
 *
 * <p>正确顺序是：**先让他看见"某某邀请你加入某某项目"，再让他决定要不要注册**。
 * 所以预览这一步不需要登录（后端也是这么做的，见 {@code InvitationService.preview}），
 * 只有"加入"那一下才要。
 *
 * <h2>没有卡片 —— 它和登录页是同一个门面的两扇</h2>
 * 被邀请的人多半从没见过 codeloom，这一页和登录页就是他最先看到的两样东西，
 * 所以它们共用一套骨架：居中的一列字，产品名在最上面，底下是主角。
 * 六行短内容套一个带边框的盒子，盒子比内容重 —— 那正是"模板感"的来源。
 *
 * <h2>主角是那一句话，所以它是标题的体量</h2>
 * 「root 邀请你加入 test」是这一页的全部信息：人名和项目名加重，连接词降成次要色。
 * 其余各行（有效期、身份、出口、说明）都是它的注脚，全部小字、降档。
 *
 * <h2>「换个账号」是一个控件，不是一句话</h2>
 * 把项目加到错的账号头上是这一页唯一会出的事故，而且没有撤销入口。
 * 所以出口贴着身份行站、是一个真的按钮 —— 一句"不是你？换个账号"
 * 混在正文里会被扫过去。
 *
 * <h2>三种人，三种主角</h2>
 * 拿着链接的人不一定是"还没加入的人"（{@code preview.viewer} 说的就是这个）：
 *
 * <ul>
 *   <li><b>还没加入</b> —— 上面那句邀请是主角，动作是「加入项目」。</li>
 *   <li><b>已经在这个项目里</b>（链接是别人发的）—— 主角得改成"你已经在 test 里了"，
 *       动作是「打开项目」。给他「加入项目」等于让他按一个什么也不会发生的按钮。</li>
 *   <li><b>就是他自己发的</b> —— 主角是"这是你自己发出去的链接"。
 *       他会想知道的那件事只有一个：**这张链接还能用吗**（能，发给对方就行）。</li>
 * </ul>
 *
 * <p>这件事从前是后端在"点下去"的那一刻才处理的，代价是：发起人点一下自己的链接，
 * 那张本来要发给对方的凭据就**被作废了**（见 {@code InvitationService.accept} 的注释）。
 * 现在提前到预览里说，那一次假动作根本不会发生。
 */
export function InvitePage() {
  const { token = '' } = useParams()
  const navigate = useNavigate()
  const status = useAuth((s) => s.status)
  const load = useAuth((s) => s.load)
  const user = useAuth((s) => s.user)
  const logout = useAuth((s) => s.logout)

  const [preview, setPreview] = useState<InvitationPreview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)

  // 这一页可能在登录关卡外面，所以 "我是谁" 要自己问一次
  useEffect(() => {
    if (status === 'unknown') void load()
  }, [status, load])

  useEffect(() => {
    let cancelled = false
    invitations
      .preview(token)
      .then((value) => {
        if (!cancelled) setPreview(value)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof ApiError ? e.message : '连不上服务器')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [token])

  async function accept() {
    setBusy(true)
    setError(null)
    try {
      const project = await invitations.accept(token)
      navigate(`/projects/${project.id}`, { replace: true })
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '连不上服务器')
    } finally {
      setBusy(false)
    }
  }

  /**
   * 换个账号：先登出，再带着"要回到这一页"去登录页。
   *
   * <p>它和匿名那条路用的是同一套约定（把来源记在 state 里，见 {@code RequireAuth}），
   * 所以登完会回到这一页，链接不会白点。
   */
  async function switchAccount() {
    await logout()
    navigate('/login', { state: { from: `/invite/${token}` } })
  }

  const canAccept = preview?.usable === true
  /** 拿着链接的人是谁 —— 服务端说了算（它看得见 cookie），见类注释 */
  const viewer = preview?.viewer ?? 'GUEST'
  /** 已经在项目里的人（包括发链接的人自己）：这一页对他没有"加入"可做 */
  const inside = viewer !== 'GUEST'

  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4">
      <div className="w-full max-w-sm space-y-10">
        {loading ? (
          // 读取中也要有产品名：来的人可能从没见过 codeloom，
          // 一屏只有"正在读取…"的话他连自己点进了什么都没有头
          <div className="space-y-6">
            <h1 className="text-sm font-semibold tracking-tight">codeloom</h1>
            <p className="text-sm text-muted-foreground">正在读取邀请…</p>
          </div>
        ) : preview === null ? (
          // 链接本身读不到：产品名还是要露 —— 不然这一屏只剩一句报错，
          // 来的人连"自己点进了什么"都不知道
          <div className="space-y-6">
            <h1 className="text-sm font-semibold tracking-tight">codeloom</h1>
            <p role="alert" className="text-sm text-destructive">
              {error ?? '连不上服务器'}
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
                    // **「为……生成」，不是「发给……」**：后者把项目名摆成了收件人
                    //（"发给 test 的链接"读起来像发给了某个人），而项目名在这一句里
                    // 的角色是"为哪个项目"。用"生成"还能和按钮上那个「生成链接」对上
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
                  // "什么时候作废"是"我该不该现在处理它"的直接答案。
                  // 写时刻而不是"24 小时内"：有效期是服务端定的，抄一个数到界面上会对不上。
                  //
                  // 已经在内、而链接不是他发的人看不到这一行 —— 这张凭据什么时候失效，
                  // 不是他的事（他能做的只有打开项目）
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

            {/* 已经在项目里的人：这一页对他只剩下"进去"这一件事 */}
            {canAccept && inside && (
              <div className="space-y-3">
                <Button
                  className="w-full"
                  onClick={() => navigate(`/projects/${preview.projectId}`)}
                >
                  打开项目
                </Button>
                {viewer === 'INVITER' && (
                  // 发起人心里那个问题只有一个：我这张链接废了吗
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
                {/* 把"本来要去哪"记在 state 里 —— 和 RequireAuth 用的是同一套约定，
                    所以登录完成之后会回到这个 /invite/<token> 上 */}
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
                {/* 身份行和它的出口在同一行：那件事（加错人）是唯一会出的事故，
                    出口得是一个看得见的控件 */}
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
                  {/* 按钮是这一页唯一要做的事，所以它下面要说清按下去之后会怎样 */}
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

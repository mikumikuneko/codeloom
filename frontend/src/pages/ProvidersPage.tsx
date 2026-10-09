import { useState, type FormEvent } from 'react'
import { Eye, EyeOff } from 'lucide-react'

import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ApiError, apiKeys, type ConfiguredKey, type ProviderPreset } from '@/lib/api'
import { useAsync } from '@/lib/useAsync'

/**
 * 供应商：**一家一行**，点「配置 / 编辑」就在原地展开成表单。
 *
 * <h2>为什么没配过的也列出来</h2>
 * 因为"支持哪些家"是产品知道的事，而这个页面的问题本来就是"我该做什么"。
 * 只列已经配好的，第一次进来就是一片空白 —— 一片空白不回答问题，只让人怀疑自己点错了地方。
 *
 * <p>（这条是照着 deepseek-harness 改的：它那句注释写的是"provider 存在但还没有 key 时，
 * **那张设置卡片就是它在这一页上的存在形式**"。）
 *
 * <h2>为什么是原地展开，而不是左边一列右边一栏</h2>
 * 编辑的字段只有三个，而"我在改哪一家"这件事在展开的卡片里看得见、不用靠位置去猜。
 * 左右分栏还会多出"右边空着的时候该显示什么"这种问题 —— 而那正是上一版空荡荡的原因。
 *
 * <h2>请求地址从头到尾不出现</h2>
 * 发请求由服务端做，地址是服务端按这一家取的。这里只有给人看和点的东西：名字、官网、密钥。
 */
export function ProvidersPage() {
  const keys = useAsync(() => apiKeys.list(), [])
  const presets = useAsync(() => apiKeys.providers(), [])

  /** 正在编辑的那一家（null = 都没展开） */
  const [editing, setEditing] = useState<string | null>(null)
  /** 刚保存过的那一家，用来给一句话的反馈 */
  const [saved, setSaved] = useState<string | null>(null)

  const error = keys.error ?? presets.error

  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <h1 className="text-xl font-medium">供应商</h1>
        <p className="text-sm text-muted-foreground">
          选一家，填上你自己的 API Key 就能用。密钥只存密文，读不回原文。
        </p>
      </div>

      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}

      <ul className="space-y-2">
        {presets.data?.map((preset) => {
          const configured = keys.data?.find((k) => k.provider === preset.id) ?? null
          return (
            <ProviderRow
              key={preset.id}
              preset={preset}
              configured={configured}
              // 从一家切到另一家时**整个表单重建**：于是输入框里的内容不会带着上一家的残留
              editing={editing === preset.id}
              saved={saved === preset.id}
              onToggle={() => {
                setSaved(null)
                setEditing((current) => (current === preset.id ? null : preset.id))
              }}
              onSaved={() => {
                setSaved(preset.id)
                setEditing(null)
                keys.reload()
              }}
              onRemoved={() => {
                setSaved(null)
                setEditing(null)
                keys.reload()
              }}
            />
          )
        })}
      </ul>
    </div>
  )
}

/**
 * 一家的卡片。收起时两行（名字 + 官网），展开时下面接一个表单。
 *
 * <p>**官网那一行只在预设里有点开的价值** —— 它是"这家在哪儿"，
 * 而不是"我们往哪儿发请求"（那个不出现）。
 */
function ProviderRow({
  preset,
  configured,
  editing,
  saved,
  onToggle,
  onSaved,
  onRemoved,
}: {
  preset: ProviderPreset
  configured: ConfiguredKey | null
  editing: boolean
  saved: boolean
  onToggle: () => void
  onSaved: () => void
  onRemoved: () => void
}) {
  return (
    <li
      className={`rounded-xl border bg-card px-5 py-4 transition-colors ${
        editing ? 'border-ring/60' : 'border-border hover:border-input'
      }`}
    >
      <div className="flex items-center gap-3">
        <ProviderIcon provider={preset.id} />

        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-sm font-medium">{preset.displayName}</span>
            {/* 状态**不用颜色用形状**：实心点+字 = 已配置，空心环 = 还没配。
                两个灰度不同的点并排是读不出差别的，而这行里只有这一个二进制信息；
                不借绿色 —— "通过/正常"的语义色在这套界面里另有主人（diff、验证） */}
            {configured ? (
              <span className="flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground">
                <span aria-hidden className="size-1.5 rounded-full bg-muted-foreground" />
                已配置
              </span>
            ) : (
              <span
                role="img"
                aria-label="还没有配置"
                title="还没有配置"
                className="size-2 shrink-0 rounded-full border border-loom-faint"
              />
            )}
          </div>
          <a
            href={preset.officialUrl}
            target="_blank"
            rel="noreferrer"
            // 和正文里的链接同一套：静止时**虚线**下划线（实线等于在喊"点我"），
            // 指上去才转实、才亮到正文色
            className="block truncate text-xs text-muted-foreground underline decoration-dotted underline-offset-[3px] transition-colors hover:decoration-solid hover:text-foreground"
          >
            {preset.officialUrl}
          </a>
        </div>

        {saved && !editing && <span className="shrink-0 text-xs text-muted-foreground">已保存</span>}
        {/* 「配置」和「编辑」是两种响度：还没配的时候，配上是这张卡唯一要做的事
            （描边）；配好之后，改它只是偶尔的动作（ghost 小字）。
            一样轻等于告诉用户"配不配都行" —— 而这一页的存在意义就是让他配上 */}
        <Button
          variant={editing || configured ? 'ghost' : 'outline'}
          size="sm"
          className="shrink-0"
          onClick={onToggle}
        >
          {editing ? '取消' : configured ? '编辑' : '配置'}
        </Button>
      </div>

      {editing && (
        <ProviderForm
          preset={preset}
          configured={configured}
          onSaved={onSaved}
          onRemoved={onRemoved}
        />
      )}
    </li>
  )
}

/**
 * 一家的表单：名字、官网、API Key。**没有请求地址**。
 *
 * <h2>密钥那一格为什么是空的，而不是把那把 key 显示出来</h2>
 * 因为**读不回来**：库里的密钥是密文（这是刻意的）。已有 key 时那一格留一排点，
 * 是"它在这儿"的意思；要换就整体重填，而右边的小眼睛只作用于**你正在输入的**那串。
 */
function ProviderForm({
  preset,
  configured,
  onSaved,
  onRemoved,
}: {
  preset: ProviderPreset
  configured: ConfiguredKey | null
  onSaved: () => void
  onRemoved: () => void
}) {
  const [name, setName] = useState(configured?.name ?? preset.displayName)
  const [apiKey, setApiKey] = useState('')
  const [revealed, setRevealed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await apiKeys.configure(preset.id, name, apiKey)
      // 提交之后**立刻清空密钥输入框**：它已经在服务端存成密文了，
      // 留在一个能被截屏、被浏览器自动填充的框里没有好处
      setApiKey('')
      setRevealed(false)
      onSaved()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '连不上服务器')
    } finally {
      setBusy(false)
    }
  }

  /**
   * 真的去删。**失败的那句话交给确认框显示**，不动表单自己的 error ——
   * 那个位置在表单下方，人刚从框里点完"删除"，眼睛还在框上。
   */
  async function remove(): Promise<string | null> {
    if (!configured) return null
    try {
      await apiKeys.remove(preset.id)
      setConfirming(false)
      onRemoved()
      return null
    } catch (e) {
      return e instanceof ApiError ? e.message : '连不上服务器'
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4 border-t border-border pl-11 pr-1 pt-4">
      <div className="space-y-1.5">
        <Label htmlFor="provider-name">供应商名称</Label>
        <Input
          id="provider-name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder={preset.displayName}
        />
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="provider-key">API Key</Label>
        <div className="relative">
          <Input
            id="provider-key"
            type={revealed ? 'text' : 'password'}
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            autoComplete="off"
            // 已经有 key 时留一排点：它是"它在这儿"的意思，**不是那把 key**（读不回来）
            placeholder={configured?.hint ? '••••••••••••' : 'sk-…'}
            className="pr-9 font-mono"
            required
          />
          <button
            type="button"
            onClick={() => setRevealed((was) => !was)}
            aria-label={revealed ? '藏起来' : '看一眼'}
            className="absolute right-1.5 top-1/2 -translate-y-1/2 rounded p-1 text-loom-faint transition-colors hover:text-foreground"
          >
            {revealed ? <EyeOff className="size-3.5" /> : <Eye className="size-3.5" />}
          </button>
        </div>
        <p className="text-xs text-loom-faint">
          {configured?.hint
            ? `现在是 ${configured.hint} · 存的是密文、读不回原文，重填即替换`
            : '只需要填这一项，请求地址已经预设好了'}
        </p>
      </div>

      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}

      <div className="flex items-center gap-2">
        <Button type="submit" size="sm" disabled={busy}>
          {busy ? '正在保存…' : '保存'}
        </Button>
        {configured && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="ml-auto text-muted-foreground"
            disabled={busy}
            onClick={() => setConfirming(true)}
          >
            删除密钥
          </Button>
        )}
      </div>

      {confirming && configured && (
        <ConfirmDialog
          title={`删除 ${configured.name} 的密钥`}
          description="删掉之后，用这一家的会话就发不出请求了。密钥只存密文，要重新填一遍才能恢复。"
          confirmLabel="删除"
          onClose={() => setConfirming(false)}
          onConfirm={remove}
        />
      )}
    </form>
  )
}

/**
 * 一家的图标。
 *
 * <p>**按 id 查一张本地图**（`public/providers/`）：图标是展示，不该让后端为它多开一个接口。
 * 认不出来的 id（自定义 provider）回落成首字母 —— 一个方块加一个字，
 * 比一个破图或者空白强。
 */
const ICONS: Record<string, string> = {
  deepseek: '/providers/deepseek.svg',
}

function ProviderIcon({ provider }: { provider: string }) {
  const icon = ICONS[provider]
  if (icon === undefined) {
    return (
      <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-selected text-sm text-muted-foreground">
        {provider.slice(0, 1).toUpperCase()}
      </span>
    )
  }
  return <img src={icon} alt="" className="size-8 shrink-0" />
}

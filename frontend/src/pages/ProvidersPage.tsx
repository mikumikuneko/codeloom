import { useState, type FormEvent } from 'react'
import { Eye, EyeOff } from 'lucide-react'

import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { errorText, apiKeys, type ConfiguredKey, type ProviderPreset } from '@/lib/api'
import { useAsync } from '@/lib/useAsync'

/**
 * 供应商：一家一行，点「配置 / 编辑」在原地展开成表单。
 *
 * 没配过的也列出来："支持哪些家"是产品知道的事，而这页的问题本来就是"我该做什么"。
 * 只列已配好的，第一次进来就是一片空白，而空白不回答问题。
 *
 * 原地展开而不是左右分栏：编辑字段只有三个，"我在改哪一家"在展开的卡片里看得见、
 * 不用靠位置猜；分栏还会多出"右边空着显示什么"的问题。
 *
 * 请求地址从头到尾不出现：发请求由服务端做，地址由服务端按这一家取。这里只有给人
 * 看和点的东西：名字、官网、密钥。
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
        <p className="text-sm text-muted-foreground">密钥只存密文，读不回原文。</p>
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
              // 从一家切到另一家时整个表单重建：输入框不会带着上一家的残留。
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
 * 一家的卡片。收起时两行（名字 + 官网），展开时下面接表单。
 *
 * 官网那一行只在预设里有点开的价值：它是"这家在哪儿"，不是"我们往哪儿发请求"。
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
            {/* 状态不用颜色用形状：实心点加字 = 已配置，空心环 = 还没配。两个灰度
                不同的点并排读不出差别，而这行只有这一个二进制信息；不借绿色——
                "通过/正常"的语义色在这套界面里另有主人（diff、验证） */}
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
            // 和正文里的链接同一套：静止时虚线（实线等于在喊"点我"），指上去才
            // 转实、才亮到正文色。
            className="block truncate text-xs text-muted-foreground underline decoration-dotted underline-offset-[3px] transition-colors hover:decoration-solid hover:text-foreground"
          >
            {preset.officialUrl}
          </a>
        </div>

        {saved && !editing && <span className="shrink-0 text-xs text-muted-foreground">已保存</span>}
        {/* 「配置」和「编辑」是两种响度：没配时配上是这张卡唯一要做的事（描边）；
            配好后改它只是偶尔动作（ghost）。一样轻等于说"配不配都行" */}
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
 * 一家的表单：名字、官网、API Key。没有请求地址。
 *
 * 密钥那格是空的而不是显示那把 key：库里的密钥是密文，读不回来。已有 key 时留
 * 一排点表示"它在这儿"；要换就整体重填，小眼睛只作用于正在输入的那串。
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
  // 没配过时按服务商的默认显示为高；保存后会把这个值明确写下去
  const [reasoningEffort, setReasoningEffort] = useState(
    configured?.reasoningEffort ?? 'high',
  )
  const [revealed, setRevealed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await apiKeys.configure(preset.id, name, apiKey, reasoningEffort)
      // 提交之后立刻清空密钥输入框：它已在服务端存成密文，留在能被截屏、被浏览器
      // 自动填充的框里没有好处。
      setApiKey('')
      setRevealed(false)
      onSaved()
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  /**
   * 真的去删。失败那句话交给确认框显示，不动表单自己的 error：人刚从框里点完
   * "删除"，眼睛还在框上。
   */
  async function remove(): Promise<string | null> {
    if (!configured) return null
    try {
      await apiKeys.remove(preset.id)
      setConfirming(false)
      onRemoved()
      return null
    } catch (e) {
      return errorText(e)
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
            // 眼睛只负责“看见现有密钥的掩码”，掩码不是可编辑的值：打开时只读，
            // 关掉之后输入框回到空串，正常输入就是换一把新 key
            value={apiKey !== '' ? apiKey : revealed ? (configured?.hint ?? '') : ''}
            readOnly={apiKey === '' && revealed && configured !== null}
            onChange={(e) => setApiKey(e.target.value)}
            autoComplete="off"
            placeholder={configured?.hint ? '••••••••••••' : 'sk-…'}
            className="pr-9 font-mono"
            // 已经配过时可以不填：留着旧密钥，只改名字或思考强度
            required={configured === null}
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
      </div>

      {preset.supportsReasoningEffort && (
        <div className="space-y-1.5">
          <Label htmlFor="provider-reasoning">思考强度</Label>
          <Select value={reasoningEffort} onValueChange={setReasoningEffort}>
            <SelectTrigger id="provider-reasoning">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="none">关闭思考</SelectItem>
              <SelectItem value="low">低</SelectItem>
              <SelectItem value="high">高</SelectItem>
              <SelectItem value="max">最高</SelectItem>
            </SelectContent>
          </Select>
        </div>
      )}

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
          description="删掉之后，用这一家的会话就发不出请求了；要重新填一遍才能恢复。"
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
 * 按 id 查一张本地图（`public/providers/`）：图标是展示，不该让后端为它多开接口。
 * 认不出来的 id（自定义 provider）回落成首字母，比破图或空白强。
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

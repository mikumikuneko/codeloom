/**
 * 把一段文本写进系统剪贴板，返回有没有写进去。
 *
 * 先用异步剪贴板；浏览器不给权限（非 https、用户拒绝）时退回临时 textarea 和
 * `execCommand`。不抛错：调用方要的是"成没成"，而给一句红字比让人再点一次更吵。
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const area = document.createElement('textarea')
    area.value = text
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    try {
      return document.execCommand('copy')
    } finally {
      area.remove()
    }
  }
}

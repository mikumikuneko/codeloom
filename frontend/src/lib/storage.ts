/**
 * localStorage 的安全读写。
 *
 * 隐私模式、存储被禁用、配额满时，localStorage 读写会抛异常。集中在这里是为了
 * 让"记不住不等于出错"这条语义在所有调用点上一致；漏掉一处会在隐私模式下弄坏界面。
 *
 * 坏数据（例如 JSON 解不出来）不在这里兜：那是调用方对格式的判断。
 */
export function readStorage(key: string): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}

export function writeStorage(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // 存不下只是"下次打开记不住"，这一次照常生效。
  }
}

export function removeStorage(key: string): void {
  try {
    window.localStorage.removeItem(key)
  } catch {
    // 同上：隐私模式下会抛，那只是"记不住"。
  }
}

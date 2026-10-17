/**
 * localStorage 的安全读写。
 *
 * <h2>为什么值得一个文件</h2>
 * 隐私模式、被禁用的存储、配额满了 —— 这些情况下 localStorage 的读写会**抛异常**。
 * 三处各写一遍 try/catch 的结果不是"多了几行"，而是那条语义（"记不住不等于出错"）
 * 只在其中两处成立，而漏掉的那一处会在隐私模式下把整个界面弄坏。
 *
 * <p>读了坏数据（比如 JSON 解不出来）**不在这里兜**：那是调用方对格式的判断。
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
    // 存不下只是"下次打开记不住"，这一次照常生效
  }
}

export function removeStorage(key: string): void {
  try {
    window.localStorage.removeItem(key)
  } catch {
    // 同上：隐私模式下会抛，那只是"记不住"
  }
}

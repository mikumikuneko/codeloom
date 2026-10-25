/**
 * 项目路径都是 POSIX 斜杠（后端给的是仓库相对路径去掉 `untitled/` 之后的那段），
 * 不按 Windows 反斜杠处理。
 */

/** 路径的最后一段。没有斜杠时就是它自己。 */
export function baseName(path: string): string {
  const cut = path.lastIndexOf('/')
  return cut < 0 ? path : path.slice(cut + 1)
}

/** 父目录；顶层路径返回空串。 */
export function parentOf(path: string): string {
  const cut = path.lastIndexOf('/')
  return cut < 0 ? '' : path.slice(0, cut)
}

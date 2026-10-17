import { describe, expect, it } from 'vitest'

import { externalUrl, hostOf, parsePresentation } from './toolPresentation'

describe('parsePresentation', () => {
  it('解析搜索结果：来源、可选项与截断标记', () => {
    const presentation = parsePresentation(JSON.stringify({
      kind: 'web_search',
      sources: [
        { url: 'https://example.com/a', title: '标题', snippet: '摘要', siteName: '站点', date: '2026-10-17' },
        { url: 'https://example.com/b' },
      ],
    }))

    expect(presentation).toEqual({
      kind: 'web_search',
      sources: [
        { url: 'https://example.com/a', title: '标题', snippet: '摘要', siteName: '站点', date: '2026-10-17' },
        { url: 'https://example.com/b' },
      ],
    })
  })

  it('缺失、坏 JSON、不认识的形状都返回 null —— 界面退回渲染文本', () => {
    expect(parsePresentation(undefined)).toBeNull()
    expect(parsePresentation('')).toBeNull()
    expect(parsePresentation('{')).toBeNull()
    expect(parsePresentation('null')).toBeNull()
    expect(parsePresentation(JSON.stringify({ kind: 'something_else' }))).toBeNull()
    expect(parsePresentation(JSON.stringify({ kind: 'web_search' }))).toBeNull()
    expect(parsePresentation(JSON.stringify({ kind: 'web_search', sources: [{ title: '没有 url' }] }))).toBeNull()
  })

  it('解析改动的起始行号；不是正整数就当没有', () => {
    expect(parsePresentation(JSON.stringify({ kind: 'diff', startLine: 3 })))
      .toEqual({ kind: 'diff', startLine: 3 })
    expect(parsePresentation(JSON.stringify({ kind: 'diff', startLine: 0 }))).toBeNull()
    expect(parsePresentation(JSON.stringify({ kind: 'diff', startLine: '3' }))).toBeNull()
  })

  it('解析写文件的事实：新建/覆盖与行数；缺一个就当没有', () => {
    expect(parsePresentation(JSON.stringify({ kind: 'write', created: true, lines: 42 })))
      .toEqual({ kind: 'write', created: true, lines: 42 })
    expect(parsePresentation(JSON.stringify({ kind: 'write', created: false, lines: 0 })))
      .toEqual({ kind: 'write', created: false, lines: 0 })
    expect(parsePresentation(JSON.stringify({ kind: 'write', created: true }))).toBeNull()
    expect(parsePresentation(JSON.stringify({ kind: 'write', lines: 3 }))).toBeNull()
    expect(parsePresentation(JSON.stringify({ kind: 'write', created: true, lines: -1 }))).toBeNull()
  })

  it('解析抓取结果：只有标题和截断标记，没有正文', () => {
    expect(parsePresentation(JSON.stringify({
      kind: 'web_fetch', title: '网页标题', truncated: true,
    }))).toEqual({ kind: 'web_fetch', title: '网页标题', truncated: true })
    expect(parsePresentation(JSON.stringify({ kind: 'web_fetch', truncated: false })))
      .toEqual({ kind: 'web_fetch', truncated: false })
  })

  it('域名解析不出来就原样显示', () => {
    expect(hostOf('https://example.com/a')).toBe('example.com')
    expect(hostOf('not a url')).toBe('not a url')
  })

  it('只把 http/https 认成可点的网址', () => {
    expect(externalUrl('https://example.com/a')).toBe('https://example.com/a')
    expect(externalUrl('http://example.com/a')).toBe('http://example.com/a')
    expect(externalUrl('file:///etc/passwd')).toBeNull()
    expect(externalUrl('javascript:alert(1)')).toBeNull()
    expect(externalUrl('not a url')).toBeNull()
  })
})

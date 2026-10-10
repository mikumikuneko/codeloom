import { describe, expect, it } from 'vitest'

import { splitPartialLine, splitStreamingBlocks } from './streamingMarkdown'

/**
 * 这里钉的是两个**"看不出来"的细节**（都写在 `splitPartialLine` 的注释里）：
 * react-markdown 给块级代码补过一个收尾换行；而"最后一行有没有写完"从交出来的
 * 那个字符串上**看不出来** —— 只能回到原始正文上判断。
 */

describe('splitStreamingBlocks', () => {
  it('空行就是块与块的分界（围栏外面）', () => {
    const { blocks, tail } = splitStreamingBlocks('第一段\n\n第二段')

    expect(blocks).toEqual(['第一段'])
    expect(tail).toBe('第二段')
  })

  it('最后一块**不封口** —— 它还在长', () => {
    expect(splitStreamingBlocks('只有一段').blocks).toEqual([])
  })

  it('围栏**里面**的空行不算分界（代码里当然可以有空行）', () => {
    const { blocks, tail } = splitStreamingBlocks('```java\nint a;\n\nint b;\n```')

    expect(blocks).toEqual([])
    expect(tail).toBe('```java\nint a;\n\nint b;\n```')
  })

  it('围栏收尾之后，后面的空行才重新算分界', () => {
    const { blocks } = splitStreamingBlocks('```java\nint a;\n```\n\n后面一段')

    expect(blocks).toEqual(['```java\nint a;\n```'])
  })

  it('**必须同一种记号收尾**：``` 不能用 ~~~ 关掉', () => {
    const { blocks, tail } = splitStreamingBlocks('```\ncode\n~~~\n\nstill inside')

    expect(blocks).toEqual([])
    expect(tail).toContain('still inside')
  })
})

describe('splitPartialLine', () => {
  it('正在写的那一行不上色，前面的行整块交给高亮器', () => {
    const source = '```java\nint a;\nint b;'

    expect(splitPartialLine('int a;\nint b;\n', source)).toEqual({
      complete: 'int a;\n',
      partial: 'int b;',
      trailingBreak: '\n',
    })
  })

  it('**第一道细节**：解析器补的那个收尾换行要裁掉，否则 partial 永远是空的', () => {
    // 源码是 "int a;"（还在写），解析器交出来的是 "int a;\n"
    const parts = splitPartialLine('int a;\n', '```java\nint a;')

    expect(parts.partial).toBe('int a;')
    expect(parts.complete).toBe('')
    expect(parts.trailingBreak).toBe('\n')
  })

  it('**第二道细节**：这一行刚敲完回车（正文末尾有换行）就不算在写', () => {
    // 交出来的字符串和上面那个一模一样 —— 区别只在原始正文上
    const parts = splitPartialLine('int a;\n', '```java\nint a;\n')

    expect(parts.partial).toBe('')
    expect(parts.complete).toBe('int a;')
  })

  it('尾巴里**前面那些已收尾的代码块**不能被摘掉最后一行', () => {
    // 这一段顶在正文末尾，但正文末尾那个代码块已经写完了 —— 它前面那一块更不该被裁
    const source = '```java\nint a;\n```\n\n中间一段话\n\n```java\nint b;'
    const parts = splitPartialLine('int b;\n', source)

    expect(parts.complete).toBe('')
    expect(parts.partial).toBe('int b;')
  })

  it('正文末尾有换行时不切', () => {
    expect(splitPartialLine('int a;\n', '一段话\n') ).toEqual({
      complete: 'int a;',
      partial: '',
      trailingBreak: '\n',
    })
  })

  it('解析器没补收尾换行时，trailingBreak 是空的', () => {
    expect(splitPartialLine('int a;', '```java\nint a;').trailingBreak).toBe('')
  })
})

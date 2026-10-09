package com.codeloom.agent.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.Optional;

/**
 * Server-Sent Events 的帧解析器。
 *
 * <p>按 <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html">SSE 规范</a>
 * 实现，**不含任何 provider 特有的知识**：
 * <ul>
 *   <li>以 {@code :} 开头的是注释，丢弃</li>
 *   <li>{@code data:} 开头的行累积起来，**遇到空行才构成一帧**（一帧可以有多行 data，
 *       按规范要用换行拼起来）</li>
 *   <li>{@code event:} / {@code id:} / {@code retry:} 是别的字段，本实现用不到，跳过</li>
 * </ul>
 *
 * <p>刻意不在这里识别 {@code [DONE]} —— 那是 OpenAI 的约定，不是 SSE 规范的一部分。
 * 让 provider 相关的知识留在 adapter 里。
 */
public final class SseParser implements AutoCloseable {

    private final BufferedReader reader;
    private final StringBuilder pendingData = new StringBuilder();
    private boolean finished;

    public SseParser(InputStream input, Charset charset) {
        this.reader = new BufferedReader(new InputStreamReader(input, charset));
    }

    public SseParser(Reader reader) {
        this.reader = new BufferedReader(reader);
    }

    /**
     * 取下一帧的 data 载荷。
     *
     * @return 载荷；流已结束则返回空。一帧里有多行 data 时按规范用 {@code \n} 拼接
     */
    public Optional<String> nextData() throws IOException {
        if (finished) {
            return Optional.empty();
        }
        pendingData.setLength(0);
        boolean hasData = false;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                // 空行 = 帧结束
                if (hasData) {
                    return Optional.of(pendingData.toString());
                }
                continue;   // 帧前的空行，忽略
            }
            if (line.startsWith(":")) {
                continue;   // 注释
            }
            if (line.startsWith("data:")) {
                if (hasData) {
                    pendingData.append('\n');
                }
                pendingData.append(stripOneLeadingSpace(line.substring("data:".length())));
                hasData = true;
            }
            // event: / id: / retry: 一律忽略
        }

        finished = true;
        // 流在帧中途断掉时，把已经攒到的数据交出去，不至于静默丢失
        return hasData ? Optional.of(pendingData.toString()) : Optional.empty();
    }

    private static String stripOneLeadingSpace(String value) {
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}

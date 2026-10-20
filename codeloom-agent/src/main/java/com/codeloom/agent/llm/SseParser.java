package com.codeloom.agent.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.Optional;

/**
 * Server-Sent Events 帧解析器，按
 * <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html">SSE 规范</a>实现。
 *
 * <p>以 {@code :} 开头的行是注释；{@code data:} 行累积到空行才构成一帧，多行按换行拼接；
 * {@code event:} / {@code id:} / {@code retry:} 忽略。它不识别 {@code [DONE]}，那是 provider 约定。
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
     * @return 载荷；流已结束则为空。多行 data 按规范用换行拼接
     * @throws IOException 读取底层流失败
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

package com.codeloom.agent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SSE 规范层面的测试，不含任何 provider 特有的知识。 */
class SseParserTest {

    @Test
    @DisplayName("data 行 + 空行构成一帧")
    void dataLineFollowedByBlankIsOneFrame() throws IOException {
        assertThat(allFrames("""
                data: {"a":1}

                data: {"a":2}

                """)).containsExactly("{\"a\":1}", "{\"a\":2}");
    }

    @Test
    @DisplayName("同一帧里的多行 data 按规范用换行拼接")
    void multipleDataLinesInOneFrameAreJoined() throws IOException {
        assertThat(allFrames("""
                data: 第一行
                data: 第二行

                """)).containsExactly("第一行\n第二行");
    }

    @Test
    @DisplayName("注释行与 event/id/retry 字段被忽略")
    void ignoresOtherFieldsAndComments() throws IOException {
        assertThat(allFrames("""
                : 这是一条 keep-alive 注释
                event: message
                id: 42
                retry: 1000
                data: 真正的载荷

                """)).containsExactly("真正的载荷");
    }

    @Test
    @DisplayName("流末尾没有空行收尾时，最后攒到的数据也不能丢")
    void flushesTrailingFrameWithoutBlankLine() throws IOException {
        assertThat(allFrames("data: 最后一帧")).containsExactly("最后一帧");
    }

    @Test
    @DisplayName("data: 后面没有空格也能解析（不是所有实现都带那个空格）")
    void toleratesMissingSpaceAfterColon() throws IOException {
        assertThat(allFrames("data:{\"a\":1}\n\n")).containsExactly("{\"a\":1}");
    }

    @Test
    @DisplayName("空流返回空，不抛异常")
    void emptyStreamYieldsNothing() throws IOException {
        assertThat(allFrames("")).isEmpty();
    }

    @Test
    @DisplayName("只有心跳注释的长连接不会产出任何帧")
    void heartbeatsAloneProduceNothing() throws IOException {
        assertThat(allFrames(": ping\n\n: ping\n\n")).isEmpty();
    }

    private static List<String> allFrames(String source) throws IOException {
        List<String> frames = new ArrayList<>();
        try (SseParser parser = new SseParser(new StringReader(source))) {
            var next = parser.nextData();
            while (next.isPresent()) {
                frames.add(next.get());
                next = parser.nextData();
            }
        }
        return frames;
    }
}

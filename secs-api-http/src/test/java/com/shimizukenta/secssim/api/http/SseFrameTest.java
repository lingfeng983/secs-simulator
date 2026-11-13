package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SSE 帧拼装：数据里的换行不能逃出 {@code data:} 字段。
 *
 * <p>此前是 {@code "event: " + type + "\ndata: " + payload + "\n\n"} 直接拼。
 * 只要载荷里出现一个裸换行，浏览器就会把它解析成新的一行字段——攻击者
 * 用一个含换行的 hostId 就能伪造 {@code event: device-link}，界面会相信
 * 一条"设备已断开"的假事件。SSE 的规矩是多行数据逐行加 {@code data:}
 * 前缀，客户端再用 {@code \n} 拼回去，JSON 语义不变。</p>
 */
class SseFrameTest {

    @Test
    @DisplayName("单行数据：帧形状不变")
    void singleLineKeepsShape() {
        assertEquals("event: device-link\ndata: {\"a\":1}\n\n",
                StreamHandler.sseFrame("device-link", "{\"a\":1}"));
    }

    @Test
    @DisplayName("数据里的换行被逐行加上 data: 前缀，不会伪造事件")
    void newlineInDataCannotForgeEvent() {
        String frame = StreamHandler.sseFrame("notice", "{\"hostId\":\"x\n\nevent: device-link\"}");

        /* 只看"以 event: 开头的行"：数据体里出现 event: 这几个字不要紧，
           要紧的是它不能成为**行首字段** */
        long eventLines = frame.lines().filter((line) -> line.startsWith("event: ")).count();
        assertEquals(1, eventLines, "只能有一行 event: —— 实际: " + frame);
        assertTrue(frame.contains("data: {\"hostId\":\"x\n"), frame);
        assertTrue(frame.contains("data: \ndata: event: device-link\"}\n"), frame);
        assertTrue(frame.endsWith("\n\n"), "帧必须以便于解析的空行结尾");
    }

    @Test
    @DisplayName("CRLF 与尾部 CR 都被规整，不会留下裸 \\r")
    void crlfIsNormalized() {
        String frame = StreamHandler.sseFrame("notice", "line1\r\nline2\r");

        assertFalse(frame.contains("\r"), "帧里不该留裸回车: " + frame);
        assertEquals("event: notice\ndata: line1\ndata: line2\n\n", frame);
    }

    @Test
    @DisplayName("事件名只留安全字符：冒号/换行都进不来")
    void eventNameIsSanitized() {
        assertEquals("event: devicelink\n", StreamHandler.sseFrame("device:link\n", "{}").split("\n")[0] + "\n");
        /* 全非法时退回 message，而不是发出一个空的 event: 行 */
        assertTrue(StreamHandler.sseFrame(":::", "{}").startsWith("event: message\n"));
    }

    @Test
    @DisplayName("空数据发一帧空 data，而不是缺字段的畸形帧")
    void emptyDataStillHasDataLine() {
        assertEquals("event: heartbeat\ndata: \n\n", StreamHandler.sseFrame("heartbeat", ""));
        assertEquals("event: heartbeat\ndata: \n\n", StreamHandler.sseFrame("heartbeat", null));
    }
}

package com.shimizukenta.secs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;

/**
 * 关闭后的 {@code executeInvokeAny} 必须是受检失败，不能抛 {@link RejectedExecutionException}。
 *
 * <p>被动端 accept 的完成回调与 {@code close()} 天然并发：连接刚建立、线程池已被
 * shutdown，回调里再提交任务就会被拒。这个异常是未受检的，一旦从 NIO 的共享回调
 * 线程冒出去，就变成"未捕获异常"（CI 把它记成 failure 注解），同一线程上其它连接的
 * 收发也会被打断。这里钉住"统一转成 ExecutionException"的契约。</p>
 */
class InvokeAnyAfterCloseTest {

    private static class TestCommunicator extends AbstractSecsCommunicator {

        TestCommunicator(AbstractSecsCommunicatorConfig config) {
            super(config);
        }

        @Override
        public java.util.Optional<SecsMessage> send(int strm, int func, boolean wbit,
                com.shimizukenta.secs.secs2.Secs2 secs2) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<SecsMessage> send(SecsMessage primary, int strm, int func,
                boolean wbit, com.shimizukenta.secs.secs2.Secs2 secs2) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    @DisplayName("executeInvokeAny on a closed communicator fails with a checked ExecutionException")
    void invokeAnyAfterCloseIsCheckedFailure() throws Exception {
        TestCommunicator comm = new TestCommunicator(new HsmsSsCommunicatorConfig());
        comm.close();

        List<Callable<Void>> tasks = List.<Callable<Void>>of(() -> null);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> comm.executeInvokeAny(tasks),
                "关闭后提交任务必须抛受检异常，不能漏出 RejectedExecutionException");

        Throwable cause = failure.getCause();
        assertInstanceOf(SecsException.class, cause, "cause 应该是可读的终止信号");
        assertTrue(cause.getMessage().contains("closing"),
                "文案要能区分关停与真失败，实际: " + cause.getMessage());
        assertInstanceOf(RejectedExecutionException.class, cause.getCause(),
                "原始拒绝原因要保留，便于排查");
    }

    @Test
    @DisplayName("the timeout overload behaves the same way after close")
    void invokeAnyWithTimeoutAfterCloseIsCheckedFailure() throws Exception {
        TestCommunicator comm = new TestCommunicator(new HsmsSsCommunicatorConfig());
        comm.close();

        List<Callable<Void>> tasks = List.<Callable<Void>>of(() -> null);
        TimeProperty timeout = TimeProperty.newInstance(0.1F);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> comm.executeInvokeAny(tasks, timeout),
                "带超时的重载也必须转成受检异常");

        assertEquals("communicator is closing", failure.getCause().getMessage());
    }
}

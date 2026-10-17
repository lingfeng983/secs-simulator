package com.shimizukenta.secs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;

/**
 * Pins down the JDK contract used by the HSMS-SS communicators:
 * {@code ExecutorService#invokeAny} cancels (interrupts) the losing tasks
 * once a winner has completed. The communicators rely on this to stop the
 * sibling task (reading / linktesting / receiving) when one of them ends.
 */
class InvokeAnyCancellationTest {

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
    @DisplayName("invokeAny interrupts the losing task")
    void invokeAnyInterruptsLosingTask() throws Exception {
        TestCommunicator comm = new TestCommunicator(new HsmsSsCommunicatorConfig());

        try {
            CountDownLatch loserStarted = new CountDownLatch(1);
            AtomicBoolean loserInterrupted = new AtomicBoolean(false);

            List<Callable<Void>> tasks = List.<Callable<Void>>of(
                    () -> {
                        /* winner: only completes once the loser is running */
                        loserStarted.await(10L, TimeUnit.SECONDS);
                        return null;
                    },
                    () -> {
                        loserStarted.countDown();
                        try {
                            Thread.sleep(TimeUnit.SECONDS.toMillis(60L));
                        }
                        catch ( InterruptedException e ) {
                            loserInterrupted.set(true);
                            throw e;
                        }
                        return null;
                    }
                    );

            long begin = System.currentTimeMillis();
            comm.executeInvokeAny(tasks);
            assertTrue(System.currentTimeMillis() - begin < 10_000L,
                    "invokeAny must return as soon as the winner completes");

            long deadline = System.currentTimeMillis() + 10_000L;
            while ( ! loserInterrupted.get() && System.currentTimeMillis() < deadline ) {
                Thread.sleep(25L);
            }

            assertTrue(loserInterrupted.get(),
                    "the losing task must be cancelled (interrupted) by invokeAny");
        }
        finally {
            comm.close();
        }
    }
}

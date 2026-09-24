package io.chatbots.grammar.bot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ChatTaskDispatcherTest {

    private final ChatTaskDispatcher dispatcher = new ChatTaskDispatcher(Executors.newVirtualThreadPerTaskExecutor());

    @AfterEach
    void tearDown() {
        dispatcher.destroy();
    }

    @Test
    void tasksOfOneChatRunInOrder() throws Exception {
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 50; i++) {
            int n = i;
            dispatcher.submit(1L, () -> {
                if (n % 7 == 0) sleep(5);
                seen.add(n);
            });
        }
        dispatcher.submit(1L, () -> { }).get(5, TimeUnit.SECONDS);
        assertThat(seen).hasSize(50).isSorted();
    }

    @Test
    void slowChatDoesNotBlockOthers() throws Exception {
        var release = new CountDownLatch(1);
        dispatcher.submit(1L, () -> await(release));
        var other = dispatcher.submit(2L, () -> { });
        other.get(2, TimeUnit.SECONDS);
        assertThat(other).isDone();
        release.countDown();
    }

    @Test
    void failingTaskDoesNotBreakTheQueue() throws Exception {
        dispatcher.submit(1L, () -> {
            throw new IllegalStateException("boom");
        });
        var ran = new CountDownLatch(1);
        dispatcher.submit(1L, ran::countDown).get(2, TimeUnit.SECONDS);
        assertThat(ran.getCount()).isZero();
    }

    @Test
    void finishedQueuesAreCleanedUp() throws Exception {
        dispatcher.submit(5L, () -> { }).get(2, TimeUnit.SECONDS);
        Thread.sleep(50);
        assertThat(dispatcher.pendingChats()).isZero();
    }

    @Test
    void unorderedTasksRun() throws Exception {
        var ran = new CountDownLatch(1);
        dispatcher.submitUnordered(3L, ran::countDown);
        assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

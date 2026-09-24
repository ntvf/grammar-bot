package io.chatbots.grammar.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs updates concurrently across chats but strictly in order within one chat, so a slow model call for
 * one user never blocks another, and a user's "regenerate" can't overtake the message it belongs to.
 */
@Component
public class ChatTaskDispatcher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ChatTaskDispatcher.class);

    private final ExecutorService executor;
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    public ChatTaskDispatcher() {
        this(Executors.newVirtualThreadPerTaskExecutor());
    }

    ChatTaskDispatcher(ExecutorService executor) {
        this.executor = executor;
    }

    public CompletableFuture<Void> submit(long chatId, Runnable task) {
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] created = new CompletableFuture[1];
        tails.compute(chatId, (key, previous) -> {
            var base = previous != null ? previous : CompletableFuture.<Void>completedFuture(null);
            created[0] = base.thenRunAsync(guarded(chatId, task), executor);
            return created[0];
        });
        var future = created[0];
        future.whenComplete((ignored, error) -> tails.remove(chatId, future));
        return future;
    }

    /** For work with no ordering requirements, like inline queries. */
    public void submitUnordered(long chatId, Runnable task) {
        executor.execute(guarded(chatId, task));
    }

    int pendingChats() {
        return tails.size();
    }

    private static Runnable guarded(long chatId, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Unhandled error while processing chat {}: {}", chatId, e.getMessage(), e);
            }
        };
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}

package io.chatbots.grammar.bot.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends the short "how it works" clip. The file is uploaded once; afterwards Telegram's file_id is reused,
 * which makes /start instant.
 */
@Component
public class DemoAnimation {

    private static final Logger log = LoggerFactory.getLogger(DemoAnimation.class);

    static final String RESOURCE = "/onboarding/demo.mp4";

    private final TelegramGateway gateway;
    private final AtomicReference<String> fileId = new AtomicReference<>();

    public DemoAnimation(TelegramGateway gateway) {
        this.gateway = gateway;
    }

    /** Returns false if the clip couldn't be sent, so the caller can fall back to plain text. */
    public boolean send(long chatId, String captionHtml, InlineKeyboardMarkup keyboard) {
        var cached = fileId.get();
        try (InputStream stream = cached == null ? DemoAnimation.class.getResourceAsStream(RESOURCE) : null) {
            if (cached == null && stream == null) {
                log.warn("Demo animation {} is missing from the classpath", RESOURCE);
                return false;
            }
            var file = cached != null ? new InputFile(cached) : new InputFile(stream, "demo.mp4");
            var message = gateway.sendAnimation(SendAnimation.builder()
                .chatId(chatId).animation(file).caption(captionHtml).parseMode("HTML")
                .width(720).height(900).replyMarkup(keyboard).build());
            remember(message);
            return true;
        } catch (Exception e) {
            log.warn("Could not send demo animation to {}: {}", chatId, e.getMessage());
            fileId.set(null);
            return false;
        }
    }

    private void remember(Message message) {
        if (message == null) return;
        if (message.getAnimation() != null) {
            fileId.compareAndSet(null, message.getAnimation().getFileId());
        } else if (message.getDocument() != null) {
            fileId.compareAndSet(null, message.getDocument().getFileId());
        }
    }
}

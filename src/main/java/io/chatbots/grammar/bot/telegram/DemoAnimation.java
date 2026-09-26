package io.chatbots.grammar.bot.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends the short "how it works" clip in the user's interface language. Each clip is uploaded once; afterwards
 * Telegram's file_id is reused, which makes /start instant.
 */
@Component
public class DemoAnimation {

    private static final Logger log = LoggerFactory.getLogger(DemoAnimation.class);

    static final String DEFAULT_LANGUAGE = "en";

    private final TelegramGateway gateway;
    private final Map<String, String> fileIds = new ConcurrentHashMap<>();

    public DemoAnimation(TelegramGateway gateway) {
        this.gateway = gateway;
    }

    /** Returns false if the clip couldn't be sent, so the caller can fall back to plain text. */
    public boolean send(long chatId, String lang, String captionHtml, InlineKeyboardMarkup keyboard) {
        var clip = DemoAnimation.class.getResource(resource(lang)) != null ? lang : DEFAULT_LANGUAGE;
        var cached = fileIds.get(clip);
        try (InputStream stream = cached == null ? DemoAnimation.class.getResourceAsStream(resource(clip)) : null) {
            if (cached == null && stream == null) {
                log.warn("Demo animation {} is missing from the classpath", resource(clip));
                return false;
            }
            var file = cached != null ? new InputFile(cached) : new InputFile(stream, "demo.mp4");
            var message = gateway.sendAnimation(SendAnimation.builder()
                .chatId(chatId).animation(file).caption(captionHtml).parseMode("HTML")
                .width(720).height(900).replyMarkup(keyboard).build());
            remember(clip, message);
            return true;
        } catch (Exception e) {
            log.warn("Could not send demo animation to {}: {}", chatId, e.getMessage());
            fileIds.remove(clip);
            return false;
        }
    }

    static String resource(String lang) {
        return "/onboarding/demo_" + lang + ".mp4";
    }

    private void remember(String clip, Message message) {
        if (message == null) return;
        if (message.getAnimation() != null) {
            fileIds.putIfAbsent(clip, message.getAnimation().getFileId());
        } else if (message.getDocument() != null) {
            fileIds.putIfAbsent(clip, message.getDocument().getFileId());
        }
    }
}

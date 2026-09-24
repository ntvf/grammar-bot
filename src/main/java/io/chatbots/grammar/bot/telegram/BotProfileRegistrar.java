package io.chatbots.grammar.bot.telegram;

import io.chatbots.grammar.service.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.description.SetMyDescription;
import org.telegram.telegrambots.meta.api.methods.description.SetMyShortDescription;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;

import java.util.List;

/**
 * Publishes the command menu and the "What can this bot do?" texts in every interface language, so a new
 * user sees a localized pitch before they even press Start.
 */
@Component
public class BotProfileRegistrar {

    private static final Logger log = LoggerFactory.getLogger(BotProfileRegistrar.class);

    private final TelegramGateway gateway;
    private final I18n i18n;
    private final String botUsername;
    private final boolean enabled;

    public BotProfileRegistrar(TelegramGateway gateway, I18n i18n,
                               @Value("${telegram.bot-username}") String botUsername,
                               @Value("${telegrambots.enabled:true}") boolean enabled) {
        this.gateway = gateway;
        this.i18n = i18n;
        this.botUsername = botUsername;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) return;
        Thread.ofVirtual().name("bot-profile").start(this::registerAll);
    }

    void registerAll() {
        register(null, I18n.DEFAULT_LANGUAGE);
        for (var lang : I18n.SUPPORTED) {
            register(lang, lang);
        }
        log.info("Registered bot commands and descriptions for {} languages", I18n.SUPPORTED.size());
    }

    private void register(String languageCode, String lang) {
        var commands = List.of(
            new BotCommand("settings", i18n.t(lang, "cmd.settings")),
            new BotCommand("language", i18n.t(lang, "cmd.language")),
            new BotCommand("help", i18n.t(lang, "cmd.help")));
        gateway.execute(SetMyCommands.builder().commands(commands).languageCode(languageCode).build());
        gateway.execute(SetMyDescription.builder()
            .description(i18n.t(lang, "bot.description", botUsername)).languageCode(languageCode).build());
        gateway.execute(SetMyShortDescription.builder()
            .shortDescription(i18n.t(lang, "bot.short_description")).languageCode(languageCode).build());
    }
}

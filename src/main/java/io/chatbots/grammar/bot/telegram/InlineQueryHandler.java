package io.chatbots.grammar.bot.telegram;

import io.chatbots.grammar.ai.AiResult;
import io.chatbots.grammar.config.AppProperties;
import io.chatbots.grammar.domain.ChatUser;
import io.chatbots.grammar.domain.Language;
import io.chatbots.grammar.service.I18n;
import io.chatbots.grammar.service.RateLimitExceededException;
import io.chatbots.grammar.service.TextService;
import io.chatbots.grammar.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerInlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.InlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.inputmessagecontent.InputTextMessageContent;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResultArticle;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResultsButton;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "@bot some text" in any chat. Telegram fires a query for almost every keystroke, so each query waits a
 * moment and is dropped if the user has typed further — only the text they paused on reaches the model.
 */
@Component
public class InlineQueryHandler {

    private static final Logger log = LoggerFactory.getLogger(InlineQueryHandler.class);

    static final int DESCRIPTION_LENGTH = 120;
    static final int CACHE_SECONDS = 30;

    private final UserService users;
    private final TextService texts;
    private final TelegramGateway gateway;
    private final I18n i18n;
    private final AppProperties properties;
    private final ConcurrentHashMap<Long, String> latestQuery = new ConcurrentHashMap<>();

    public InlineQueryHandler(UserService users, TextService texts, TelegramGateway gateway, I18n i18n,
                              AppProperties properties) {
        this.users = users;
        this.texts = texts;
        this.gateway = gateway;
        this.i18n = i18n;
        this.properties = properties;
    }

    public void handle(InlineQuery query) {
        var from = query.getFrom();
        var text = query.getQuery() == null ? "" : query.getQuery().strip();
        var lang = I18n.resolve(from.getLanguageCode());
        if (text.codePointCount(0, text.length()) < properties.inlineMinLength()) {
            answer(query.getId(), List.of(), i18n.t(lang, "inline.setup"));
            return;
        }
        if (!isLatestAfterPause(from.getId(), query.getId())) return;

        var user = users.touch(new UserService.Profile(from.getId(), from.getUserName(), from.getFirstName(),
            from.getLanguageCode()));
        lang = user.getUiLanguage();
        try {
            var result = texts.processInline(user, text);
            answer(query.getId(), List.of(article(query.getId(), user, result)), i18n.t(lang, "inline.setup"));
        } catch (RateLimitExceededException e) {
            answer(query.getId(), List.of(), i18n.t(lang, "inline.limit"));
        } catch (Exception e) {
            log.info("Inline query failed for {}: {}", from.getId(), e.getMessage());
            answer(query.getId(), List.of(), i18n.t(lang, "inline.setup"));
        } finally {
            latestQuery.remove(from.getId(), query.getId());
        }
    }

    boolean isLatestAfterPause(long userId, String queryId) {
        latestQuery.put(userId, queryId);
        if (properties.inlineDebounceMs() > 0) {
            try {
                Thread.sleep(properties.inlineDebounceMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return queryId.equals(latestQuery.get(userId));
    }

    private InlineQueryResultArticle article(String queryId, ChatUser user, AiResult result) {
        var lang = user.getUiLanguage();
        var title = switch (result.action()) {
            case UNCHANGED -> i18n.t(lang, "inline.unchanged");
            case TRANSLATED -> i18n.t(lang, "inline.translated", translatedLabel(user, result));
            case CORRECTED -> i18n.t(lang, "inline.corrected");
        };
        var description = result.text().length() > DESCRIPTION_LENGTH
            ? result.text().substring(0, DESCRIPTION_LENGTH - 1) + "…" : result.text();
        return InlineQueryResultArticle.builder()
            .id(queryId)
            .title(title)
            .description(description)
            .inputMessageContent(InputTextMessageContent.builder().messageText(result.text()).build())
            .build();
    }

    private static String translatedLabel(ChatUser user, AiResult result) {
        var from = Language.fromCode(result.detectedLanguage()).map(Language::flag).orElse("🌐");
        return from + " → " + user.getTargetLanguage().label();
    }

    private void answer(String queryId, List<InlineQueryResultArticle> results, String buttonText) {
        gateway.execute(AnswerInlineQuery.builder()
            .inlineQueryId(queryId)
            .results(List.copyOf(results))
            .cacheTime(CACHE_SECONDS)
            .isPersonal(true)
            .button(InlineQueryResultsButton.builder().text(buttonText).startParameter("inline").build())
            .build());
    }
}

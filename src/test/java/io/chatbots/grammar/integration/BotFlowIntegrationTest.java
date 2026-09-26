package io.chatbots.grammar.integration;

import io.chatbots.grammar.ai.AiResult;
import io.chatbots.grammar.bot.ChatTaskDispatcher;
import io.chatbots.grammar.bot.telegram.InlineQueryHandler;
import io.chatbots.grammar.bot.telegram.TelegramBotService;
import io.chatbots.grammar.domain.Language;
import io.chatbots.grammar.domain.Mode;
import io.chatbots.grammar.domain.TextAction;
import io.chatbots.grammar.domain.TextEntry;
import io.chatbots.grammar.domain.TextStatus;
import io.chatbots.grammar.domain.Tone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.AnswerInlineQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResultArticle;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** End-to-end conversations: Telegram update in, Telegram API calls out. */
class BotFlowIntegrationTest extends IntegrationTest {

    private static final long CHAT = 101L;
    private static final long ADMIN = 999L;

    @Autowired TelegramBotService bot;
    @Autowired ChatTaskDispatcher dispatcher;
    @Autowired InlineQueryHandler inlineQueries;

    // ---- first contact ----

    @Test
    void start_newUser_getsDemoInTheirLanguage_andCanWriteRightAway() {
        send(CHAT, Updates.text(CHAT, 1, "/start ads_spring-2026!", "uk"));

        var animation = last(SendAnimation.class);
        assertThat(animation.getCaption()).contains("Привіт, Ann").contains("🇬🇧 English");
        assertThat(animation.getReplyMarkup()).isNull();
        assertThat(sent(SendMessage.class)).isEmpty();
        var user = chatUsers.findByChatId(CHAT).orElseThrow();
        assertThat(user.getUiLanguage()).isEqualTo("uk");
        assertThat(user.getSource()).isEqualTo("ads_spring-2026");
        assertThat(user.isOnboarded()).isTrue();
        assertThat(user.getMode()).isEqualTo(Mode.SMART);
        assertThat(user.getTargetLanguage()).isEqualTo(Language.EN);
    }

    @Test
    void start_returningUser_getsWelcomeBack() {
        send(CHAT, Updates.text(CHAT, 1, "/start"));
        send(CHAT, Updates.text(CHAT, 2, "/start@test_grammar_bot"));

        assertThat(last(SendMessage.class).getText()).contains("Welcome back").contains("🇬🇧 English");
        assertThat(sent(SendAnimation.class)).hasSize(1);
    }

    @Test
    void interfaceLanguage_followsTheTelegramApp() {
        send(CHAT, Updates.text(CHAT, 1, "/start", "uk"));
        send(CHAT, Updates.text(CHAT, 2, "/help", "de"));
        assertThat(chatUsers.findByChatId(CHAT).orElseThrow().getUiLanguage()).isEqualTo("de");
    }

    // ---- texts and results ----

    @Test
    void text_isAnsweredWithResultAndButtons_asReplyToTheOriginal() {
        send(CHAT, Updates.text(CHAT, 7, "i has went to the shop yesterday"));

        var reply = last(SendMessage.class);
        assertThat(reply.getText()).isEqualTo(FIXED);
        assertThat(reply.getParseMode()).isEqualTo("HTML");
        assertThat(reply.getReplyParameters().getMessageId()).isEqualTo(7);
        var labels = labels(reply.getReplyMarkup());
        assertThat(labels).containsExactly("📋 Copy", "🎨 Style · 🪶", "🌐 Language", "💡 What changed");
        var copy = buttons((InlineKeyboardMarkup) reply.getReplyMarkup()).stream()
            .filter(b -> b.getCopyText() != null).findFirst().orElseThrow();
        assertThat(copy.getCopyText().getText()).isEqualTo(FIXED);

        var entry = onlyEntry();
        assertThat(entry.getStatus()).isEqualTo(TextStatus.DONE);
        assertThat(entry.getSourceMessageId()).isEqualTo(7);
        assertThat(entry.getResultMessageId()).isNotNull();
        assertThat(aiInteractions.count()).isEqualTo(1);
    }

    @Test
    void caption_isProcessedLikeText() {
        send(CHAT, Updates.caption(CHAT, 8, "photo of me shop"));
        assertThat(last(SendMessage.class).getText()).isEqualTo(FIXED);
    }

    @Test
    void messageWithoutText_getsHint() {
        send(CHAT, Updates.caption(CHAT, 8, null));
        assertThat(last(SendMessage.class).getText()).contains("Send me text");
    }

    @Test
    void unchangedText_saysSoAndHasNoExplainButton() {
        aiReturns("All good here.", TextAction.UNCHANGED);
        send(CHAT, Updates.text(CHAT, 7, "All good here."));

        var reply = last(SendMessage.class);
        assertThat(reply.getText()).contains("Looks good");
        assertThat(labels(reply.getReplyMarkup())).doesNotContain("💡 What changed");
    }

    @Test
    void tooLongText_isRejectedWithoutCallingTheModel() {
        send(CHAT, Updates.text(CHAT, 7, "a".repeat(2001)));
        assertThat(last(SendMessage.class).getText()).contains("2001").contains("2000");
        verify(aiGateway, org.mockito.Mockito.never()).complete(anyString(), anyString(), anyDouble());
    }

    @Test
    void dailyLimit_isEnforced() {
        for (int i = 0; i < 5; i++) send(CHAT, Updates.text(CHAT, 10 + i, "text " + i));
        send(CHAT, Updates.text(CHAT, 20, "one more"));
        assertThat(last(SendMessage.class).getText()).contains("today’s limit of 5");
    }

    @Test
    void admin_isNotRateLimited() {
        for (int i = 0; i < 6; i++) send(ADMIN, Updates.text(ADMIN, 10 + i, "text " + i));
        assertThat(last(SendMessage.class).getText()).isEqualTo(FIXED);
    }

    @Test
    void modelFailure_offersRetry_andRetryReplacesTheErrorWithTheResult() {
        aiFails();
        send(CHAT, Updates.text(CHAT, 7, "i has went"));
        var error = last(SendMessage.class);
        assertThat(error.getText()).contains("couldn’t process");
        var retryData = buttons((InlineKeyboardMarkup) error.getReplyMarkup()).getFirst().getCallbackData();
        assertThat(onlyEntry().getStatus()).isEqualTo(TextStatus.FAILED);

        aiReturns(FIXED, TextAction.CORRECTED);
        send(CHAT, Updates.tap(CHAT, 1001, retryData));
        assertThat(last(EditMessageText.class).getText()).isEqualTo(FIXED);
        var entry = onlyEntry();
        assertThat(entry.getStatus()).isEqualTo(TextStatus.DONE);
        assertThat(entry.getResultMessageId()).isEqualTo(1001);
    }

    // ---- buttons under a result ----

    @Test
    void regenerate_showsWorkingStateThenNewVersion() {
        var id = processOne();
        aiReturns("Yesterday I went to the shop.", TextAction.CORRECTED);

        send(CHAT, Updates.tap(CHAT, 500, "r:g:" + id + ":"));

        var working = sent(EditMessageReplyMarkup.class).getFirst();
        assertThat(labels(working.getReplyMarkup())).containsExactly("⏳ Working on it…");
        assertThat(last(EditMessageText.class).getText()).isEqualTo("Yesterday I went to the shop.");
        verify(aiGateway).complete(anyString(), contains("<previous>\n" + FIXED), eq(0.9));
        assertThat(textEntries.findById(id).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void regenerate_sameText_tellsTheUser() {
        var id = processOne();
        send(CHAT, Updates.tap(CHAT, 500, "r:g:" + id + ":"));
        assertThat(last(AnswerCallbackQuery.class).getText()).contains("best version");
    }

    @Test
    void regenerate_failure_restoresButtons() {
        var id = processOne();
        aiFails();
        send(CHAT, Updates.tap(CHAT, 500, "r:g:" + id + ":"));
        assertThat(last(AnswerCallbackQuery.class).getText()).contains("couldn’t process");
        assertThat(labels(last(EditMessageReplyMarkup.class).getReplyMarkup())).contains("📋 Copy");
        assertThat(textEntries.findById(id).orElseThrow().getResultText()).isEqualTo(FIXED);
    }

    @Test
    void stylePicker_offersFormalCasualAndShorter() {
        var id = processOne();

        send(CHAT, Updates.tap(CHAT, 500, "r:s:" + id + ":"));
        assertThat(labels(last(EditMessageReplyMarkup.class).getReplyMarkup()))
            .containsExactly("🎩 Formal", "😎 Casual", "✂️ Shorter", "⬅️ Back");
    }

    @Test
    void shorter_dropsASentencePerTap_thenWords_thenDisappears() {
        aiReturns("Hi! I went to the shop. It was closed.", TextAction.CORRECTED);
        send(CHAT, Updates.text(CHAT, 7, "hi! i has went to the shop. it was closed."));
        var id = onlyEntry().getId();

        aiReturns("I went to the shop, but it was closed.", TextAction.CORRECTED);
        send(CHAT, Updates.tap(CHAT, 500, "r:k:" + id + ":"));
        verify(aiGateway).complete(contains("exactly one sentence shorter"),
            contains("Hi! I went to the shop. It was closed."), anyDouble());
        var once = last(EditMessageText.class);
        assertThat(once.getText()).isEqualTo("I went to the shop, but it was closed.");

        send(CHAT, Updates.tap(CHAT, 500, "r:s:" + id + ":"));
        assertThat(labels(last(EditMessageReplyMarkup.class).getReplyMarkup())).contains("✂️ Shorter");

        aiReturns("The shop was closed.", TextAction.CORRECTED);
        send(CHAT, Updates.tap(CHAT, 500, "r:k:" + id + ":"));
        verify(aiGateway).complete(contains("at most half of them"), anyString(), anyDouble());
        var entry = textEntries.findById(id).orElseThrow();
        assertThat(entry.getResultText()).isEqualTo("The shop was closed.");
        assertThat(entry.isShortest()).isTrue();
        assertThat(entry.getAction()).isEqualTo(TextAction.CORRECTED);

        send(CHAT, Updates.tap(CHAT, 500, "r:s:" + id + ":"));
        assertThat(labels(last(EditMessageReplyMarkup.class).getReplyMarkup())).doesNotContain("✂️ Shorter");
    }

    @Test
    void style_isAppliedAndMarked_andTappingAgainReverts() {
        var id = processOne();
        aiReturns("I visited the store yesterday.", TextAction.CORRECTED);

        send(CHAT, Updates.tap(CHAT, 500, "r:t:" + id + ":FORMAL"));
        assertThat(labels(sent(EditMessageReplyMarkup.class).getFirst().getReplyMarkup()))
            .contains("⏳ Working on it…", "😎 Casual", "⬅️ Back").doesNotContain("🎩 Formal");
        var formal = last(EditMessageText.class);
        assertThat(formal.getText()).isEqualTo("I visited the store yesterday.");
        assertThat(labels(formal.getReplyMarkup())).contains("🎨 Style · 🎩");
        verify(aiGateway).complete(contains("formal register"), anyString(), anyDouble());

        send(CHAT, Updates.tap(CHAT, 500, "r:t:" + id + ":FORMAL"));
        assertThat(textEntries.findById(id).orElseThrow().getTone()).isEqualTo(Tone.NATURAL);
    }

    @Test
    void languagePicker_opensAndTranslates() {
        var id = processOne();

        send(CHAT, Updates.tap(CHAT, 500, "r:l:" + id + ":"));
        assertThat(labels(last(EditMessageReplyMarkup.class).getReplyMarkup())).containsExactly(
            "✓ 🇬🇧 English", "🇩🇪 Deutsch", "🇪🇸 Español", "🇮🇹 Italiano", "🇫🇷 Français", "🇵🇱 Polski",
            "🇺🇦 Українська", "⬅️ Back");

        send(CHAT, Updates.tap(CHAT, 500, "r:b:" + id + ":"));
        assertThat(labels(last(EditMessageReplyMarkup.class).getReplyMarkup())).contains("🎨 Style · 🪶");

        aiReturns("Ich war gestern im Laden.", TextAction.TRANSLATED,
            new AiResult.Change("has went", "went", "past tense"));
        send(CHAT, Updates.tap(CHAT, 500, "r:L:" + id + ":de"));
        var translated = last(EditMessageText.class);
        assertThat(translated.getText()).isEqualTo("Ich war gestern im Laden.");
        assertThat(labels(translated.getReplyMarkup())).doesNotContain("💡 What changed");
        verify(aiGateway).complete(contains("into German"), anyString(), anyDouble());

        send(CHAT, Updates.tap(CHAT, 500, "r:L:" + id + ":pt"));
        assertThat(textEntries.findById(id).orElseThrow().getTargetLanguage()).isEqualTo(Language.DE);
    }

    @Test
    void nativeLanguage_isTranslated_andUsedLanguagesAppearAsQuickButtons() {
        aiReturns("I went to the shop yesterday.", "uk", TextAction.TRANSLATED);
        send(CHAT, Updates.text(CHAT, 7, "я вчора ходив у магазин", "uk"));
        verify(aiGateway).complete(contains("If the text is written in English, correct it"), anyString(), anyDouble());
        var first = last(SendMessage.class);
        assertThat(labels(first.getReplyMarkup())).containsExactly(
            "📋 Копіювати", "🎨 Стиль · 🪶", "🇺🇦 Українська", "🌐 Мова");

        aiReturns("Wczoraj poszedłem do sklepu.", "uk", TextAction.TRANSLATED);
        var id = onlyEntry().getId();
        var quickData = "r:L:" + id + ":pl";
        send(CHAT, Updates.tap(CHAT, 500, "r:l:" + id + ":", "uk"));
        send(CHAT, Updates.tap(CHAT, 500, quickData, "uk"));
        assertThat(textEntries.findById(id).orElseThrow().getTargetLanguage()).isEqualTo(Language.PL);

        aiReturns("I will be late.", "uk", TextAction.TRANSLATED);
        send(CHAT, Updates.text(CHAT, 8, "я запізнюсь", "uk"));
        assertThat(labels(last(SendMessage.class).getReplyMarkup())).containsExactly(
            "📋 Копіювати", "🎨 Стиль · 🪶", "🇵🇱 Polski", "🇺🇦 Українська", "🌐 Мова");
    }

    @Test
    void quickLanguageTap_showsWorkingOnTheResultItself() {
        aiReturns("I went to the shop yesterday.", "uk", TextAction.TRANSLATED);
        send(CHAT, Updates.text(CHAT, 7, "я вчора ходив у магазин", "uk"));
        var id = onlyEntry().getId();

        aiReturns("Я вчора ходив у магазин.", "uk", TextAction.CORRECTED);
        send(CHAT, Updates.tap(CHAT, 500, "r:L:" + id + ":uk", "uk"));
        assertThat(labels(sent(EditMessageReplyMarkup.class).getFirst().getReplyMarkup()))
            .contains("⏳ Працюю…", "📋 Копіювати", "🌐 Мова");
    }

    @Test
    void usedForeignLanguage_isCorrectedInPlace_notTranslated() {
        var id = processOne();
        aiReturns("Wczoraj poszedłem do sklepu.", TextAction.TRANSLATED);
        send(CHAT, Updates.tap(CHAT, 500, "r:L:" + id + ":pl"));

        aiReturns("Idę do sklepu.", "pl", TextAction.CORRECTED);
        send(CHAT, Updates.text(CHAT, 8, "ide do sklepu", "uk"));
        verify(aiGateway).complete(contains("If the text is written in English or Polish, correct it in that same "
            + "language"), anyString(), anyDouble());
        var latest = textEntries.findAll().stream().max(java.util.Comparator.comparing(TextEntry::getId)).orElseThrow();
        assertThat(latest.getTargetLanguage()).isEqualTo(Language.PL);
        assertThat(labels(last(SendMessage.class).getReplyMarkup())).contains("🇬🇧 English");
    }

    @Test
    void nativeLanguage_isNeverCorrectedInPlace_evenAfterTranslatingIntoIt() {
        aiReturns("I went.", "uk", TextAction.TRANSLATED);
        send(CHAT, Updates.text(CHAT, 7, "я ходив", "uk"));
        aiReturns("Я ходив.", "uk", TextAction.CORRECTED);
        send(CHAT, Updates.tap(CHAT, 500, "r:L:" + onlyEntry().getId() + ":uk", "uk"));

        send(CHAT, Updates.text(CHAT, 8, "я прийшов", "uk"));
        verify(aiGateway, org.mockito.Mockito.never()).complete(contains("or Ukrainian"), anyString(), anyDouble());
    }

    @Test
    void languagePicker_putsTheUsersFrequentLanguagesFirst() {
        var id = processOne();
        aiReturns("Wczoraj poszedłem do sklepu.", TextAction.TRANSLATED);
        send(CHAT, Updates.tap(CHAT, 500, "r:L:" + id + ":pl"));

        send(CHAT, Updates.text(CHAT, 8, "another text"));
        var second = textEntries.findAll().stream().mapToLong(e -> e.getId()).max().orElseThrow();
        send(CHAT, Updates.tap(CHAT, 501, "r:l:" + second + ":"));
        assertThat(labels(last(EditMessageReplyMarkup.class).getReplyMarkup()))
            .startsWith("✓ 🇬🇧 English", "🇵🇱 Polski", "🇩🇪 Deutsch");
    }

    @Test
    void explain_togglesTheChangeList() {
        var id = processOne();

        send(CHAT, Updates.tap(CHAT, 500, "r:e:" + id + ":"));
        var shown = last(EditMessageText.class);
        assertThat(shown.getText()).contains("<s>has went</s> → <b>went</b>");
        assertThat(labels(shown.getReplyMarkup())).contains("🙈 Hide");

        send(CHAT, Updates.tap(CHAT, 500, "r:e:" + id + ":"));
        assertThat(last(EditMessageText.class).getText()).isEqualTo(FIXED);
    }

    @Test
    void longResult_copyResendsTheTextAsCodeBlock() {
        var longText = "Yesterday I went to the shop. ".repeat(10).strip();
        aiReturns(longText, TextAction.CORRECTED);
        send(CHAT, Updates.text(CHAT, 7, "i has went"));
        var copy = buttons((InlineKeyboardMarkup) last(SendMessage.class).getReplyMarkup()).getFirst();
        assertThat(copy.getText()).isEqualTo("📋 Copy");
        assertThat(copy.getCopyText()).isNull();

        send(CHAT, Updates.tap(CHAT, 500, copy.getCallbackData()));
        assertThat(last(SendMessage.class).getText()).isEqualTo("<pre>" + longText + "</pre>");
    }

    @Test
    void explain_withoutChanges_showsToast() {
        aiReturns("Fine.", TextAction.UNCHANGED);
        send(CHAT, Updates.text(CHAT, 7, "Fine."));
        send(CHAT, Updates.tap(CHAT, 500, "r:e:" + onlyEntry().getId() + ":"));
        assertThat(last(AnswerCallbackQuery.class).getText()).contains("Nothing to explain");
    }

    @Test
    void someoneElsesEntry_orGarbage_isNeverTouched() {
        var id = processOne();
        long stranger = 202L;

        send(stranger, Updates.tap(stranger, 500, "r:g:" + id + ":"));
        assertThat(last(AnswerCallbackQuery.class).getText()).contains("too old");

        send(CHAT, Updates.tap(CHAT, 500, "r:t:" + id + ":EVIL"));
        send(CHAT, Updates.tap(CHAT, 500, "garbage"));
        send(CHAT, Updates.tap(CHAT, 500, "z:z:0:"));
        assertThat(sent(AnswerCallbackQuery.class)).hasSize(4);
        assertThat(textEntries.findById(id).orElseThrow().getVersion()).isZero();
    }

    @Test
    void editingTheOriginal_updatesTheAnswerInPlace() {
        processOne();
        aiReturns("I went to the market yesterday.", TextAction.CORRECTED);

        send(CHAT, Updates.edited(CHAT, 7, "i has went to the market yesterday"));

        var edit = last(EditMessageText.class);
        assertThat(edit.getText()).isEqualTo("I went to the market yesterday.");
        assertThat(edit.getMessageId()).isEqualTo(onlyEntry().getResultMessageId());
    }

    @Test
    void editingAnUnknownMessage_isIgnored() {
        send(CHAT, Updates.edited(CHAT, 77, "something"));
        assertThat(sent(EditMessageText.class)).isEmpty();
    }

    // ---- commands ----

    @Test
    void settingsAndLanguageCommands_bothOpenTheDefaultLanguagePicker() {
        send(CHAT, Updates.text(CHAT, 1, "/settings"));
        var picker = last(SendMessage.class);
        assertThat(picker.getText()).contains("translate into by default");
        assertThat(labels(picker.getReplyMarkup())).startsWith("✓ 🇬🇧 English");

        send(CHAT, Updates.text(CHAT, 2, "/language"));
        send(CHAT, Updates.tap(CHAT, 70, "p:l:0:pl"));
        assertThat(last(EditMessageText.class).getText()).isEqualTo("✅ Default language: 🇵🇱 Polski");
        assertThat(chatUsers.findByChatId(CHAT).orElseThrow().getTargetLanguage()).isEqualTo(Language.PL);
    }

    @Test
    void buttonsFromTheOldSetupAndSettings_areIgnored() {
        send(CHAT, Updates.text(CHAT, 1, "/start"));
        send(CHAT, Updates.tap(CHAT, 50, "o:m:0:FIX"));
        send(CHAT, Updates.tap(CHAT, 60, "s:m:0:TRANSLATE"));
        assertThat(sent(AnswerCallbackQuery.class)).hasSize(2);
        assertThat(chatUsers.findByChatId(CHAT).orElseThrow().getMode()).isEqualTo(Mode.SMART);
    }

    @Test
    void help_sendsDemoWithInstructions() {
        send(CHAT, Updates.text(CHAT, 1, "/help"));
        assertThat(last(SendAnimation.class).getCaption()).contains("How to use me").contains("@test_grammar_bot")
            .contains("🇬🇧 English");
    }

    @Test
    void demoClip_isUploadedOnceThenReusedByFileId() {
        send(CHAT, Updates.text(CHAT, 1, "/help"));
        send(CHAT, Updates.text(CHAT, 2, "/help"));
        var clips = sent(SendAnimation.class);
        assertThat(clips.getLast().getAnimation().getAttachName()).isEqualTo("file-id-1");
    }

    @Test
    void stats_onlyForAdmins() {
        send(CHAT, Updates.text(CHAT, 1, "/stats"));
        assertThat(last(SendMessage.class).getText()).contains("don’t know that command");

        send(ADMIN, Updates.text(ADMIN, 1, "/stats"));
        assertThat(last(SendMessage.class).getText()).contains("Bot statistics").contains("Users: 2");
    }

    @Test
    void unknownCommand() {
        send(CHAT, Updates.text(CHAT, 1, "/foo"));
        assertThat(last(SendMessage.class).getText()).contains("/help");
    }

    @Test
    void groupMessages_areIgnored() {
        send(-5L, Updates.groupText(-5L, "hello"));
        assertThat(sent(SendMessage.class)).isEmpty();
    }

    @Test
    void blockingTheBot_deletesUserAndTexts() {
        processOne();
        send(CHAT, Updates.kicked(CHAT));
        assertThat(chatUsers.findByChatId(CHAT)).isEmpty();
        assertThat(textEntries.count()).isZero();
    }

    // ---- inline mode ----

    @Test
    void inline_shortQuery_offersSetupOnly() {
        inlineQueries.handle(Updates.inline(CHAT, "q1", "hi").getInlineQuery());
        var answer = last(AnswerInlineQuery.class);
        assertThat(answer.getResults()).isEmpty();
        assertThat(answer.getButton().getStartParameter()).isEqualTo("inline");
    }

    @Test
    void inline_returnsReadyToSendResult() {
        aiReturns("Ich komme später.", TextAction.TRANSLATED);
        inlineQueries.handle(Updates.inline(CHAT, "q2", "i will come later").getInlineQuery());

        var article = (InlineQueryResultArticle) last(AnswerInlineQuery.class).getResults().getFirst();
        assertThat(article.getTitle()).contains("🇬🇧 → 🇬🇧 English");
        assertThat(article.getDescription()).isEqualTo("Ich komme später.");
        assertThat(textEntries.count()).isZero();
        assertThat(aiInteractions.count()).isEqualTo(1);
    }

    @Test
    void inline_respectsDailyLimit() {
        for (int i = 0; i < 6; i++) {
            inlineQueries.handle(Updates.inline(CHAT, "q" + i, "some text " + i).getInlineQuery());
        }
        var answer = last(AnswerInlineQuery.class);
        assertThat(answer.getResults()).isEmpty();
        assertThat(answer.getButton().getText()).contains("limit");
    }

    @Test
    void inline_modelFailure_answersGracefully() {
        aiFails();
        inlineQueries.handle(Updates.inline(CHAT, "q9", "some text").getInlineQuery());
        assertThat(last(AnswerInlineQuery.class).getResults()).isEmpty();
    }

    @Test
    void inline_routedThroughBot() throws Exception {
        bot.getUpdatesConsumer().consume(List.of(Updates.inline(CHAT, "q3", "some text")));
        for (int i = 0; i < 50 && sent(AnswerInlineQuery.class).isEmpty(); i++) Thread.sleep(50);
        assertThat(sent(AnswerInlineQuery.class)).hasSize(1);
    }

    // ---- helpers ----

    /** Routes the update and waits until the chat's queue has processed it. */
    private void send(long chatId, Update update) {
        bot.getUpdatesConsumer().consume(List.of(update));
        try {
            dispatcher.submit(chatId, () -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private long processOne() {
        send(CHAT, Updates.text(CHAT, 7, "i has went to the shop yesterday"));
        return onlyEntry().getId();
    }

    private TextEntry onlyEntry() {
        var all = textEntries.findAll();
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    private static List<InlineKeyboardButton> buttons(Object markup) {
        if (markup == null) return List.of();
        return ((InlineKeyboardMarkup) markup).getKeyboard().stream().flatMap(List::stream).toList();
    }

    private static List<String> labels(Object markup) {
        return buttons(markup).stream().map(InlineKeyboardButton::getText).filter(Objects::nonNull).toList();
    }

    @SuppressWarnings("unused")
    private static AiResult.Change change(String from, String to) {
        return new AiResult.Change(from, to, "why");
    }
}

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
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
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

    // ---- onboarding ----

    @Test
    void start_newUser_getsDemoAndFirstQuestionInTheirLanguage() {
        send(CHAT, Updates.text(CHAT, 1, "/start ads_spring-2026!", "uk"));

        var animation = last(SendAnimation.class);
        assertThat(animation.getCaption()).contains("Привіт, Ann");
        assertThat(last(SendMessage.class).getText()).contains("Крок 1 з 2");
        var user = chatUsers.findByChatId(CHAT).orElseThrow();
        assertThat(user.getUiLanguage()).isEqualTo("uk");
        assertThat(user.getSource()).isEqualTo("ads_spring-2026");
        assertThat(user.isOnboarded()).isFalse();
    }

    @Test
    void onboarding_smartMode_asksLanguage_thenFinishes_thenRunsExample() {
        send(CHAT, Updates.text(CHAT, 1, "/start"));

        send(CHAT, Updates.tap(CHAT, 50, "o:m:0:SMART"));
        assertThat(last(EditMessageText.class).getText()).contains("Step 2 of 2");

        send(CHAT, Updates.tap(CHAT, 50, "o:l:0:de"));
        var done = last(EditMessageText.class);
        assertThat(done.getText()).contains("All set").contains("🇩🇪 Deutsch").contains("@test_grammar_bot");
        assertThat(buttons(done.getReplyMarkup())).extracting(InlineKeyboardButton::getStyle).contains("success");
        var user = chatUsers.findByChatId(CHAT).orElseThrow();
        assertThat(user.isOnboarded()).isTrue();
        assertThat(user.getTargetLanguage()).isEqualTo(Language.DE);

        send(CHAT, Updates.tap(CHAT, 50, "o:x:0:"));
        var messages = sent(SendMessage.class);
        assertThat(messages.get(messages.size() - 2).getText()).contains("a few mistakes");
        assertThat(last(SendMessage.class).getText()).isEqualTo(FIXED);
    }

    @Test
    void onboarding_fixMode_skipsLanguageQuestion() {
        send(CHAT, Updates.text(CHAT, 1, "/start"));
        send(CHAT, Updates.tap(CHAT, 50, "o:m:0:FIX"));

        assertThat(last(EditMessageText.class).getText()).contains("All set").doesNotContain("Result language");
        var user = chatUsers.findByChatId(CHAT).orElseThrow();
        assertThat(user.isOnboarded()).isTrue();
        assertThat(user.getMode()).isEqualTo(Mode.FIX);
    }

    @Test
    void start_returningUser_getsWelcomeBack() {
        send(CHAT, Updates.text(CHAT, 1, "/start"));
        send(CHAT, Updates.tap(CHAT, 50, "o:m:0:FIX"));
        send(CHAT, Updates.text(CHAT, 2, "/start@test_grammar_bot"));

        assertThat(last(SendMessage.class).getText()).contains("Welcome back");
        assertThat(sent(SendAnimation.class)).hasSize(1);
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
        assertThat(labels).contains("🔄 Another version", "📋 Copy", "🎩 Formal", "😎 Casual", "✂️ Shorter",
            "🌐 Language · 🇬🇧", "💡 What changed");
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
        assertThat(labels(working.getReplyMarkup())).startsWith("⏳ Working on it…", "📋 Copy").contains("🎩 Formal");
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
        assertThat(labels(last(EditMessageReplyMarkup.class).getReplyMarkup())).contains("🔄 Another version");
        assertThat(textEntries.findById(id).orElseThrow().getResultText()).isEqualTo(FIXED);
    }

    @Test
    void style_isAppliedAndMarked_andTappingAgainReverts() {
        var id = processOne();
        aiReturns("I visited the store yesterday.", TextAction.CORRECTED);

        send(CHAT, Updates.tap(CHAT, 500, "r:t:" + id + ":FORMAL"));
        var formal = last(EditMessageText.class);
        assertThat(formal.getText()).isEqualTo("I visited the store yesterday.");
        assertThat(labels(formal.getReplyMarkup())).contains("✓ 🎩 Formal");
        verify(aiGateway).complete(contains("formal register"), anyString(), anyDouble());

        send(CHAT, Updates.tap(CHAT, 500, "r:t:" + id + ":FORMAL"));
        assertThat(textEntries.findById(id).orElseThrow().getTone()).isEqualTo(Tone.NATURAL);
    }

    @Test
    void languagePicker_opensAndTranslates() {
        var id = processOne();

        send(CHAT, Updates.tap(CHAT, 500, "r:l:" + id + ":"));
        assertThat(labels(last(EditMessageReplyMarkup.class).getReplyMarkup()))
            .startsWith("✓ 🇬🇧 English").contains("🇩🇪 Deutsch", "⬅️ Back");

        send(CHAT, Updates.tap(CHAT, 500, "r:b:" + id + ":"));
        assertThat(labels(last(EditMessageReplyMarkup.class).getReplyMarkup())).contains("🔄 Another version");

        aiReturns("Ich war gestern im Laden.", TextAction.TRANSLATED);
        send(CHAT, Updates.tap(CHAT, 500, "r:L:" + id + ":de"));
        assertThat(last(EditMessageText.class).getText()).isEqualTo("Ich war gestern im Laden.");
        verify(aiGateway).complete(contains("into German"), anyString(), anyDouble());
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

    // ---- settings and commands ----

    @Test
    void settings_everyOptionCanBeChanged() {
        send(CHAT, Updates.text(CHAT, 1, "/settings"));
        assertThat(last(SendMessage.class).getText()).contains("Settings").contains("✨ <b>Smart</b>");

        send(CHAT, Updates.tap(CHAT, 60, "s:m:0:"));
        assertThat(labels(last(EditMessageText.class).getReplyMarkup())).contains("✓ ✨ Smart");
        send(CHAT, Updates.tap(CHAT, 60, "s:m:0:TRANSLATE"));
        assertThat(last(AnswerCallbackQuery.class).getText()).isEqualTo("✅ Saved");

        send(CHAT, Updates.tap(CHAT, 60, "s:l:0:"));
        send(CHAT, Updates.tap(CHAT, 60, "s:l:0:pl"));
        send(CHAT, Updates.tap(CHAT, 60, "s:t:0:"));
        assertThat(labels(last(EditMessageText.class).getReplyMarkup())).contains("✓ 🪶 Natural");
        send(CHAT, Updates.tap(CHAT, 60, "s:t:0:BUSINESS"));
        send(CHAT, Updates.tap(CHAT, 60, "s:e:0:"));
        send(CHAT, Updates.tap(CHAT, 60, "s:u:0:"));
        send(CHAT, Updates.tap(CHAT, 60, "s:u:0:de"));
        assertThat(last(EditMessageText.class).getText()).contains("Einstellungen");

        var user = chatUsers.findByChatId(CHAT).orElseThrow();
        assertThat(user.getMode()).isEqualTo(Mode.TRANSLATE);
        assertThat(user.getTargetLanguage()).isEqualTo(Language.PL);
        assertThat(user.getTone()).isEqualTo(Tone.BUSINESS);
        assertThat(user.isAutoExplain()).isTrue();
        assertThat(user.getUiLanguage()).isEqualTo("de");

        send(CHAT, Updates.tap(CHAT, 60, "s:h:0:"));
        send(CHAT, Updates.tap(CHAT, 60, "s:c:0:"));
        assertThat(last(DeleteMessage.class).getMessageId()).isEqualTo(60);
    }

    @Test
    void settings_unsupportedInterfaceLanguage_isIgnored() {
        send(CHAT, Updates.text(CHAT, 1, "/settings"));
        send(CHAT, Updates.tap(CHAT, 60, "s:u:0:ja"));
        assertThat(chatUsers.findByChatId(CHAT).orElseThrow().getUiLanguage()).isEqualTo("en");
    }

    @Test
    void autoExplain_showsChangesImmediately() {
        send(CHAT, Updates.text(CHAT, 1, "/settings"));
        send(CHAT, Updates.tap(CHAT, 60, "s:e:0:"));
        send(CHAT, Updates.text(CHAT, 7, "i has went"));
        assertThat(last(SendMessage.class).getText()).contains("<blockquote>");
    }

    @Test
    void languageCommand_switchesFixModeToSmart() {
        send(CHAT, Updates.text(CHAT, 1, "/start"));
        send(CHAT, Updates.tap(CHAT, 50, "o:m:0:FIX"));
        send(CHAT, Updates.text(CHAT, 2, "/language"));
        assertThat(last(SendMessage.class).getText()).contains("Which language");

        send(CHAT, Updates.tap(CHAT, 70, "p:l:0:fr"));
        assertThat(last(EditMessageText.class).getText()).contains("🇫🇷 Français");
        var user = chatUsers.findByChatId(CHAT).orElseThrow();
        assertThat(user.getTargetLanguage()).isEqualTo(Language.FR);
        assertThat(user.getMode()).isEqualTo(Mode.SMART);
    }

    @Test
    void help_sendsDemoWithInstructions() {
        send(CHAT, Updates.text(CHAT, 1, "/help"));
        assertThat(last(SendAnimation.class).getCaption()).contains("How to use me").contains("@test_grammar_bot");
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

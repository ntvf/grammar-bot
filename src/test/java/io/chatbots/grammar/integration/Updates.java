package io.chatbots.grammar.integration;

import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberBanned;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberUpdated;
import org.telegram.telegrambots.meta.api.objects.inlinequery.InlineQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/** Builders for incoming Telegram updates. */
final class Updates {

    private Updates() {
    }

    static User user(long id, String languageCode) {
        var user = new User(id, "Ann", false);
        user.setUserName("ann");
        user.setLanguageCode(languageCode);
        return user;
    }

    static Message message(long chatId, int messageId, String text, String languageCode) {
        var message = new Message();
        message.setMessageId(messageId);
        message.setChat(new Chat(chatId, "private"));
        message.setFrom(user(chatId, languageCode));
        message.setText(text);
        return message;
    }

    static Update text(long chatId, int messageId, String text) {
        return text(chatId, messageId, text, "en");
    }

    static Update text(long chatId, int messageId, String text, String languageCode) {
        var update = new Update();
        update.setMessage(message(chatId, messageId, text, languageCode));
        return update;
    }

    static Update caption(long chatId, int messageId, String caption) {
        var message = message(chatId, messageId, null, "en");
        message.setCaption(caption);
        var update = new Update();
        update.setMessage(message);
        return update;
    }

    static Update edited(long chatId, int messageId, String text) {
        var update = new Update();
        update.setEditedMessage(message(chatId, messageId, text, "en"));
        return update;
    }

    static Update groupText(long chatId, String text) {
        var message = message(chatId, 1, text, "en");
        message.setChat(new Chat(chatId, "group"));
        var update = new Update();
        update.setMessage(message);
        return update;
    }

    static Update tap(long chatId, int messageId, String data) {
        var query = new CallbackQuery("cb-" + messageId + "-" + data, user(chatId, "en"),
            message(chatId, messageId, "menu", "en"), null, data, null, null);
        var update = new Update();
        update.setCallbackQuery(query);
        return update;
    }

    static Update inline(long userId, String queryId, String query) {
        var update = new Update();
        update.setInlineQuery(new InlineQuery(queryId, user(userId, "en"), query, "0"));
        return update;
    }

    static Update kicked(long chatId) {
        var member = new ChatMemberUpdated(new Chat(chatId, "private"), user(chatId, "en"), 0,
            null, new ChatMemberBanned(user(1, "en"), 0), null, false, false);
        var update = new Update();
        update.setMyChatMember(member);
        return update;
    }
}

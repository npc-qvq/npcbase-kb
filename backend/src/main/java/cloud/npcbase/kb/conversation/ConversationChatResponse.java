package cloud.npcbase.kb.conversation;

import java.util.List;

/**
 * 返回一次会话提问后新增的用户、系统或小C消息。
 *
 * @param conversation 更新后的会话摘要
 * @param messages 本次提问产生的消息列表
 * @author NPC
 * @date 2026-07-16 15:10:00
 */
public record ConversationChatResponse(ConversationView conversation, List<ConversationMessageView> messages) {
}

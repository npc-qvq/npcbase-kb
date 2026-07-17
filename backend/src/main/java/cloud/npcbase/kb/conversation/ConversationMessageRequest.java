package cloud.npcbase.kb.conversation;

/**
 * 用户在指定历史会话中发送的问题和小C提示词。
 *
 * @param question 用户输入的问题或启动口令
 * @param assistantPrompt 提交给小C的角色提示词
 * @author NPC
 * @date 2026-07-16 15:10:00
 */
public record ConversationMessageRequest(String question, String assistantPrompt) {
}

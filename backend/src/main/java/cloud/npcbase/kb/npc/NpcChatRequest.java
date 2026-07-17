package cloud.npcbase.kb.npc;

/**
 * 提交给小C助手的问题和角色提示词。
 *
 * @param question 用户提出的问题
 * @param assistantPrompt 用户补充的小C角色提示词
 * @author NPC
 * @date 2026-07-16 14:05:00
 */
public record NpcChatRequest(String question, String assistantPrompt) {
}

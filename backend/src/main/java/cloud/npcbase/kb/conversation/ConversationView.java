package cloud.npcbase.kb.conversation;

import java.time.LocalDateTime;

/**
 * 向历史会话列表展示的会话摘要。
 *
 * @param id 会话主键
 * @param title 会话标题
 * @param npcStarted 小C是否已在该会话启动
 * @param updatedAt 会话最后更新时间
 * @author NPC
 * @date 2026-07-16 15:10:00
 */
public record ConversationView(String id, String title, boolean npcStarted, LocalDateTime updatedAt) {
}

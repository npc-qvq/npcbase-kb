package cloud.npcbase.kb.conversation;

import cloud.npcbase.kb.npc.NpcCitation;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 向前端展示的一条持久化会话消息。
 *
 * @param id 消息主键
 * @param role 消息角色
 * @param content 消息正文
 * @param citations 小C回答引用的资料列表
 * @param createdAt 消息创建时间
 * @author NPC
 * @date 2026-07-16 15:10:00
 */
public record ConversationMessageView(String id, String role, String content, List<NpcCitation> citations,
                                      LocalDateTime createdAt) {
}

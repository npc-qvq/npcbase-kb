package cloud.npcbase.kb.npc;

import java.util.List;

/**
 * 返回小C生成的回答和对应知识库资料来源。
 *
 * @param answer 小C生成的回答内容
 * @param citations 回答使用的资料切片列表
 * @author NPC
 * @date 2026-07-16 14:05:00
 */
public record NpcChatResponse(String answer, List<NpcCitation> citations) {
}

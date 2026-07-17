package cloud.npcbase.kb.npc;

/**
 * 小C回答中使用的一条知识库资料切片。
 *
 * @param documentId 文档主键
 * @param documentTitle 文档标题
 * @param chunkNo 文档内的切片序号
 * @param excerpt 用于展示的资料摘要
 * @author NPC
 * @date 2026-07-16 14:05:00
 */
public record NpcCitation(String documentId, String documentTitle, int chunkNo, String excerpt) {
}

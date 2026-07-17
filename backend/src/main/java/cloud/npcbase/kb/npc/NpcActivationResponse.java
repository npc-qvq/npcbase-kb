package cloud.npcbase.kb.npc;

/**
 * 返回小C助手当前启动状态。
 *
 * @param active 小C是否已允许调用模型回答
 * @param message 启动处理结果说明
 * @author NPC
 * @date 2026-07-16 14:05:00
 */
public record NpcActivationResponse(boolean active, String message) {
}

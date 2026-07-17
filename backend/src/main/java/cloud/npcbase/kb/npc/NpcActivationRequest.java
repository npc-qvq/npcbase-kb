package cloud.npcbase.kb.npc;

/**
 * 请求启动小C助手的口令参数。
 *
 * @param command 用户输入的启动口令
 * @author NPC
 * @date 2026-07-16 14:05:00
 */
public record NpcActivationRequest(String command) {
}

package cloud.npcbase.kb.access;

/**
 * 返回当前浏览器的权限、测试会话和公开体验次数。
 *
 * @param unlocked 是否已通过唯一密钥解锁
 * @param demoConversationId 公开测试会话主键
 * @param remainingMessages 当前匿名访客剩余公开消息次数；解锁后返回消息上限
 * @param messageLimit 单个匿名访客的公开消息上限
 * @param publicProvider 公开测试会话固定使用的模型提供商
 * @author NPC
 * @date 2026-08-06 16:01:37
 */
public record AccessStatusResponse(
        boolean unlocked,
        String demoConversationId,
        int remainingMessages,
        int messageLimit,
        String publicProvider) {
}

package cloud.npcbase.kb.npc;

/**
 * 返回小C助手当前启动状态。
 *
 * @param active 小C是否已允许调用模型回答
 * @param message 启动处理结果说明
 * @param provider 当前激活的对话模型提供商名称，可能为 null
 * @author NPC
 * @date 2026-07-16 14:05:00
 */
public record NpcActivationResponse(boolean active, String message, String provider) {

    /**
     * 向后兼容的双参数构造方法，provider 默认为 null。
     *
     * @param active 小C是否已允许调用模型回答
     * @param message 启动处理结果说明
     */
    public NpcActivationResponse(boolean active, String message) {
        this(active, message, null);
    }
}

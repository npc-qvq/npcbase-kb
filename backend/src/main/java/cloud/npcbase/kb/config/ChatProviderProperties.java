package cloud.npcbase.kb.config;

import lombok.Data;

/**
 * 保存单个对话大模型提供商的 OpenAI 兼容服务配置。
 *
 * @author NPC
 * @date 2026-07-16 16:23:00
 */
@Data
public class ChatProviderProperties {

    /**
     * 控制该提供商的对话能力是否可被小C启动指令启用。
     */
    private boolean enabled;

    /**
     * 对话模型 OpenAI 兼容接口的基础地址。
     */
    private String baseUrl;

    /**
     * 调用对话模型时携带的访问密钥。
     */
    private String apiKey;

    /**
     * 小C生成回答时使用的对话模型名称。
     */
    private String model;
}

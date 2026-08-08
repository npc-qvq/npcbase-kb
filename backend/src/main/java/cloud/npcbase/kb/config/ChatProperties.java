package cloud.npcbase.kb.config;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保存小C调用对话大模型所需的多提供商配置，支持运行时切换。
 *
 * @author NPC
 * @date 2026-07-16 16:23:00
 */
@Data
public class ChatProperties {

    /**
     * 当前激活的对话模型提供商名称，对应 providers 中的键。
     */
    private String activeProvider = "zhipu";

    /**
     * 全部对话模型提供商配置，键为提供商名称（如 deepseek、zhipu）。
     */
    private Map<String, ChatProviderProperties> providers = new LinkedHashMap<>();
}

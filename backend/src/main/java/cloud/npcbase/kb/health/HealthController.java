package cloud.npcbase.kb.health;

import cloud.npcbase.kb.config.KbProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 提供知识库服务运行状态查询接口。
 *
 * @author NPC
 * @date 2026-07-15 18:27:07
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    /**
     * 知识库服务配置。
     */
    private final KbProperties properties;

    /**
     * 创建服务健康检查控制器。
     *
     * @param properties 知识库服务配置
     */
    public HealthController(KbProperties properties) {
        this.properties = properties;
    }

    /**
     * 返回服务运行状态和当前功能配置摘要。
     *
     * @return 服务状态信息
     */
    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("chatEnabled", properties.getChat().isEnabled());
        result.put("embeddingEnabled", properties.getEmbedding().isEnabled());
        result.put("qdrantCollection", properties.getQdrant().getCollection());
        return result;
    }
}

package cloud.npcbase.kb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定知识库服务的根配置属性。
 *
 * @author NPC
 * @date 2026-07-16 10:09:20
 */
@Data
@ConfigurationProperties(prefix = "kb")
public class KbProperties {

    /**
     * 文档原文件和解析文本的存储根目录。
     */
    private String storageRoot;

    /**
     * Qdrant 向量数据库连接配置。
     */
    private QdrantProperties qdrant = new QdrantProperties();

    /**
     * 小C启动后用于 DeepSeek 回答的对话模型配置。
     */
    private ChatProperties chat = new ChatProperties();

    /**
     * 文档入库和语义查询时用于 BGE-M3 的向量模型配置。
     */
    private EmbeddingProperties embedding = new EmbeddingProperties();

}

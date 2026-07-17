package cloud.npcbase.kb.config;

import lombok.Data;

/**
 * 保存 Qdrant 向量数据库连接配置。
 *
 * @author NPC
 * @date 2026-07-16 10:09:20
 */
@Data
public class QdrantProperties {

    /**
     * Qdrant REST 服务完整基础地址，支持 HTTPS 反向代理地址。
     */
    private String url;

    /**
     * Qdrant REST API 访问密钥，未开启鉴权时可以留空。
     */
    private String apiKey;

    /**
     * Qdrant 服务主机地址。
     */
    private String host;

    /**
     * Qdrant REST 服务端口。
     */
    private int port;

    /**
     * 存储知识库切片向量的 collection 名称。
     */
    private String collection;
}

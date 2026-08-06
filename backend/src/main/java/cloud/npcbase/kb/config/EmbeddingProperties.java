package cloud.npcbase.kb.config;

import lombok.Data;

/**
 * 保存文档与查询文本生成向量所需的 OpenAI 兼容服务配置。
 *
 * @author NPC
 * @date 2026-07-16 16:23:00
 */
@Data
public class EmbeddingProperties {

    /**
     * 控制硅基流动向量生成和 Qdrant 语义检索是否启用。
     */
    private boolean enabled;

    /**
     * 向量模型 OpenAI 兼容接口的基础地址。
     */
    private String baseUrl;

    /**
     * 调用向量模型时携带的硅基流动访问密钥。
     */
    private String apiKey;

    /**
     * 文档和查询必须共同使用的向量模型名称。
     */
    private String model;

    /**
     * 单次向量接口调用最多提交的文本条数，避免超出上游请求限制。
     */
    private int batchSize = 16;

    /**
     * 控制是否使用同一硅基流动服务对向量召回结果进行二次精排。
     */
    private boolean rerankEnabled;

    /**
     * 对召回切片重新计算相关度时使用的重排模型名称。
     */
    private String rerankModel;

    /**
     * 重排后保留给本地回答或大模型上下文的最大切片数。
     */
    private int rerankTopN = 3;
}

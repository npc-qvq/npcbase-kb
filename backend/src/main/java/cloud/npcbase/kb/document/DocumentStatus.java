package cloud.npcbase.kb.document;

/**
 * 知识库文档的解析和索引状态。
 *
 * @author NPC
 * @date 2026-07-15 18:27:07
 */
public enum DocumentStatus {

    /**
     * 文档已上传，等待解析。
     */
    UPLOADED,

    /**
     * 文档正在提取文本。
     */
    PARSING,

    /**
     * 文档正在写入向量索引。
     */
    INDEXING,

    /**
     * 文档已完成索引。
     */
    INDEXED,

    /**
     * 文档处理失败。
     */
    FAILED
}

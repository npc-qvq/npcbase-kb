package cloud.npcbase.kb.ingest;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 记录一段可被关键词和语义检索的文档文本。
 *
 * @author NPC
 * @date 2026-07-16 10:09:20
 */
@Data
@TableName("kb_document_chunk")
public class DocumentChunk {

    /**
     * 文档切片主键。
     */
    @TableId
    private String id;

    /**
     * 所属文档主键。
     */
    @TableField("document_id")
    private String documentId;

    /**
     * 文档内的切片序号。
     */
    @TableField("chunk_no")
    private int chunkNo;

    /**
     * 切片文本内容。
     */
    private String content;

    /**
     * Qdrant 中对应的向量 point 主键。
     */
    @TableField("qdrant_point_id")
    private String qdrantPointId;

    /**
     * 切片创建时间。
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * MyBatis-Plus 映射切片数据所需的无参构造方法。
     */
    public DocumentChunk() {
    }

    /**
     * 创建新的文档切片。
     *
     * @param documentId 所属文档主键
     * @param chunkNo 文档内切片序号
     * @param content 切片文本内容
     */
    public DocumentChunk(String documentId, int chunkNo, String content) {
        this.id = UUID.randomUUID().toString();
        this.documentId = documentId;
        this.chunkNo = chunkNo;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 设置 Qdrant point 主键。
     *
     * @param qdrantPointId Qdrant point 主键
     */
}

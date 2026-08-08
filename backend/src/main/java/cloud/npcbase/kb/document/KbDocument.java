package cloud.npcbase.kb.document;

import cloud.npcbase.kb.common.SnowflakeIdGenerator;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 保存知识库文档元数据和索引处理状态。
 *
 * @author NPC
 * @date 2026-07-16 09:47:49
 */
@Data
@TableName("kb_document")
public class KbDocument {

    /**
     * 文档主键。
     */
    @TableId
    private String id;

    /**
     * 文档标题。
     */
    private String title;

    /**
     * 用户上传的原始文件名。
     */
    private String originalFilename;

    /**
     * 原始文件扩展名。
     */
    private String fileType;

    /**
     * 原始文件大小，单位为字节。
     */
    private long fileSize;

    /**
     * 原始文件本地存储路径。
     */
    private String storagePath;

    /**
     * 解析文本本地存储路径。
     */
    private String parsedPath;

    /**
     * 当前解析和索引状态。
     */
    private DocumentStatus status;

    /**
     * 最近一次处理失败原因。
     */
    private String failureReason;

    /**
     * 文档创建时间。
     */
    private LocalDateTime createdAt;

    /**
     * 文档最近更新时间。
     */
    private LocalDateTime updatedAt;

    /**
     * 创建供 MyBatis-Plus 映射使用的空文档实体。
     */
    public KbDocument() {
    }

    /**
     * 根据上传文件创建待解析知识库文档。
     *
     * @param title            文档标题
     * @param originalFilename 原始文件名
     * @param fileType         原始文件扩展名
     * @param fileSize         原始文件大小
     * @param storagePath      原始文件存储路径
     */
    public KbDocument(String title, String originalFilename, String fileType, long fileSize, String storagePath) {
        this.id = SnowflakeIdGenerator.nextId();
        this.title = title;
        this.originalFilename = originalFilename;
        this.fileType = fileType;
        this.fileSize = fileSize;
        this.storagePath = storagePath;
        this.status = DocumentStatus.UPLOADED;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * 将文档标记为文本解析中。
     */
    public void markParsing() {
        status = DocumentStatus.PARSING;
        touch();
    }

    /**
     * 将文档标记为向量索引中。
     */
    public void markIndexing() {
        status = DocumentStatus.INDEXING;
        touch();
    }

    /**
     * 将文档标记为索引完成。
     *
     * @param parsedPath 解析文本存储路径
     */
    public void markIndexed(String parsedPath) {
        this.parsedPath = parsedPath;
        status = DocumentStatus.INDEXED;
        failureReason = null;
        touch();
    }

    /**
     * 将文档标记为处理失败。
     *
     * @param reason 失败原因
     */
    public void markFailed(String reason) {
        status = DocumentStatus.FAILED;
        failureReason = reason;
        touch();
    }

    /**
     * 更新文档最近修改时间。
     */
    private void touch() {
        updatedAt = LocalDateTime.now();
    }
}

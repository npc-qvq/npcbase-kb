package cloud.npcbase.kb.ingest;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 记录文档解析、切片和索引的异步执行状态。
 *
 * @author NPC
 * @date 2026-07-16 09:47:49
 */
@Data
@TableName("kb_ingest_task")
public class IngestTask {

    /**
     * 解析任务主键。
     */
    @TableId
    private String id;

    /**
     * 待处理文档主键。
     */
    @TableField("document_id")
    private String documentId;

    /**
     * 当前任务状态。
     */
    private String status;

    /**
     * 已执行尝试次数。
     */
    @TableField("attempt_count")
    private int attemptCount;

    /**
     * 最近一次失败的错误信息。
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 任务创建时间。
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 任务最近更新时间。
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * MyBatis-Plus 映射任务数据所需的无参构造方法。
     */
    public IngestTask() {
    }

    /**
     * 为指定文档创建待处理解析任务。
     *
     * @param documentId 待处理文档主键
     */
    public IngestTask(String documentId) {
        this.id = UUID.randomUUID().toString();
        this.documentId = documentId;
        this.status = "PENDING";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = createdAt;
    }

    /**
     * 将任务标记为正在执行，并增加执行次数。
     */
    public void start() {
        status = "RUNNING";
        attemptCount++;
        touch();
    }

    /**
     * 将任务标记为执行成功。
     */
    public void done() {
        status = "DONE";
        errorMessage = null;
        touch();
    }

    /**
     * 将任务标记为执行失败并保存错误信息。
     *
     * @param message 失败原因
     */
    public void fail(String message) {
        status = "FAILED";
        errorMessage = message;
        touch();
    }

    /**
     * 更新任务最近修改时间。
     */
    private void touch() {
        updatedAt = LocalDateTime.now();
    }

}

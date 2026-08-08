package cloud.npcbase.kb.conversation;

import cloud.npcbase.kb.common.SnowflakeIdGenerator;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 保存一条小C知识库对话的标题、启动状态和时间信息。
 *
 * @author NPC
 * @date 2026-07-16 15:10:00
 */
@Data
@TableName("kb_conversation")
public class Conversation {

    /**
     * 会话主键。
     */
    @TableId
    private String id;

    /**
     * 会话展示标题。
     */
    private String title;

    /**
     * 该会话的小C是否已收到启动口令。
     */
    private boolean npcStarted;

    /**
     * 会话创建时间。
     */
    private LocalDateTime createdAt;

    /**
     * 会话最后更新时间。
     */
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标识。
     */
    private boolean deleted;

    /**
     * 创建供 MyBatis-Plus 映射使用的空会话实体。
     */
    public Conversation() {
    }

    /**
     * 创建新的小C知识库会话。
     *
     * @return 待持久化的小C知识库会话
     */
    public static Conversation create() {
        Conversation conversation = new Conversation();
        conversation.id = SnowflakeIdGenerator.nextId();
        conversation.title = "新对话";
        conversation.npcStarted = false;
        conversation.deleted = false;
        conversation.createdAt = LocalDateTime.now();
        conversation.updatedAt = conversation.createdAt;
        return conversation;
    }

    /**
     * 将当前会话标记为已启动小C。
     */
    public void startNpc() {
        this.npcStarted = true;
        touch();
    }

    /**
     * 将当前会话切换回不调用大模型的资料检索模式。
     */
    public void stopNpc() {
        this.npcStarted = false;
        touch();
    }

    /**
     * 使用首个实际问题更新会话标题。
     *
     * @param title 新的会话标题
     */
    public void updateTitle(String title) {
        if (title != null && !title.isBlank() && "新对话".equals(this.title)) {
            this.title = title.trim();
        }
        touch();
    }
    /**
     * 使用用户输入的新名称重命名当前会话。
     *
     * @param title 已通过业务层校验的新会话名称
     * @throws IllegalArgumentException 当会话名称为空时抛出
     */
    public void rename(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("请输入会话名称");
        }
        this.title = title.trim();
        touch();
    }


    /**
     * 标记会话已逻辑删除。
     */
    public void markDeleted() {
        this.deleted = true;
        touch();
    }

    /**
     * 更新会话最后修改时间。
     */
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}

package cloud.npcbase.kb.conversation;

import cloud.npcbase.kb.common.SnowflakeIdGenerator;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 保存一条会话中的用户、小C或系统消息。
 *
 * @author NPC
 * @date 2026-07-16 15:10:00
 */
@Data
@TableName("kb_chat_message")
public class ConversationMessage {

    /**
     * 消息主键。
     */
    @TableId
    private String id;

    /**
     * 所属会话主键。
     */
    private String conversationId;

    /**
     * 消息角色，取值为 user、assistant 或 system。
     */
    private String role;

    /**
     * 消息正文。
     */
    private String content;

    /**
     * 小C回答引用的资料 JSON 字符串。
     */
    private String citationsJson;

    /**
     * 消息创建时间。
     */
    private LocalDateTime createdAt;

    /**
     * 创建供 MyBatis-Plus 映射使用的空消息实体。
     */
    public ConversationMessage() {
    }

    /**
     * 创建一条待持久化的会话消息。
     *
     * @param conversationId 所属会话主键
     * @param role 消息角色
     * @param content 消息正文
     * @param citationsJson 回答引用的资料 JSON 字符串
     * @return 待持久化的会话消息
     */
    public static ConversationMessage create(String conversationId, String role, String content, String citationsJson) {
        ConversationMessage message = new ConversationMessage();
        message.id = SnowflakeIdGenerator.nextId();
        message.conversationId = conversationId;
        message.role = role;
        message.content = content;
        message.citationsJson = citationsJson;
        message.createdAt = LocalDateTime.now();
        return message;
    }
}

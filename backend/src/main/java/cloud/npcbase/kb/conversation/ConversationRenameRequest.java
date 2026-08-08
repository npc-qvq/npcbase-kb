package cloud.npcbase.kb.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 接收用户为历史会话设置的新展示名称。
 *
 * @param title 去除首尾空格前的会话名称
 * @author NPC
 * @date 2026-08-06 15:30:11
 */
public record ConversationRenameRequest(
        @NotBlank(message = "请输入会话名称")
        @Size(max = 60, message = "会话名称不能超过60个字符")
        String title) {
}

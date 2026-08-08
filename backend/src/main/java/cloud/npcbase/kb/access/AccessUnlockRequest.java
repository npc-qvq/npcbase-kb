package cloud.npcbase.kb.access;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 接收用户输入的唯一访问密钥。
 *
 * @param key 用户输入的访问密钥明文，仅用于本次后端校验
 * @author NPC
 * @date 2026-08-06 16:01:37
 */
public record AccessUnlockRequest(
        @NotBlank(message = "请输入访问密钥")
        @Size(max = 128, message = "访问密钥长度不合法")
        String key) {
}

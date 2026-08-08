package cloud.npcbase.kb.config;

import lombok.Data;

/**
 * 保存公开体验、唯一密钥和访问凭证相关配置。
 *
 * @author NPC
 * @date 2026-08-06 16:01:37
 */
@Data
public class AccessProperties {

    /**
     * 使用 PBKDF2 生成的唯一访问密钥哈希。
     */
    private String keyHash;

    /**
     * 用于签名访问凭证和访客指纹的服务器私钥。
     */
    private String tokenSecret;

    /**
     * 无密钥访客唯一允许发送消息的测试会话主键。
     */
    private String demoConversationId;

    /**
     * 公开测试会话固定使用的模型提供商名称。
     */
    private String publicProvider = "zhipu";

    /**
     * 单个匿名访客可成功发送的最大消息数量。
     */
    private int publicMessageLimit = 5;

    /**
     * 同一网络地址每天允许的公开消息数量。
     */
    private int publicIpDailyLimit = 20;

    /**
     * 全站每天允许的公开消息总数量。
     */
    private int publicGlobalDailyLimit = 100;

    /**
     * 密钥解锁凭证有效小时数。
     */
    private int tokenTtlHours = 24;

    /**
     * 同一网络地址在限制窗口内允许的密钥尝试次数。
     */
    private int unlockMaxAttempts = 5;

    /**
     * 密钥尝试次数限制窗口分钟数。
     */
    private int unlockWindowMinutes = 10;

    /**
     * 是否信任反向代理写入的 X-Forwarded-For；仅在代理会覆盖该请求头时启用。
     */
    private boolean trustForwardedFor;

    /**
     * Cookie 是否仅通过 HTTPS 发送；线上环境必须启用。
     */
    private boolean cookieSecure;
}

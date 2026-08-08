package cloud.npcbase.kb.access;

import org.springframework.http.HttpStatus;

/**
 * 表示访问密钥、公开体验额度或敏感操作权限异常。
 *
 * @author NPC
 * @date 2026-08-06 16:01:37
 */
public class AccessException extends RuntimeException {

    /**
     * 返回给前端用于识别错误类型的稳定错误码。
     */
    private final String code;

    /**
     * 当前权限异常对应的 HTTP 状态。
     */
    private final HttpStatus status;

    /**
     * 创建访问权限异常。
     *
     * @param status HTTP 响应状态
     * @param code 稳定错误码
     * @param message 面向用户展示的错误消息
     */
    public AccessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * 获取稳定错误码。
     *
     * @return 前端可识别的错误码
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取 HTTP 响应状态。
     *
     * @return 当前异常对应的 HTTP 状态
     */
    public HttpStatus getStatus() {
        return status;
    }
}

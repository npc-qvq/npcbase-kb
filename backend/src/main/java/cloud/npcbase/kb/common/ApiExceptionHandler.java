package cloud.npcbase.kb.common;

import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 将接口业务异常和参数校验异常转换为统一的 JSON 错误响应。
 *
 * @author NPC
 * @date 2026-07-15 18:27:07
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 接口异常日志记录器。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 处理参数不合法导致的业务异常。
     *
     * @param exception 参数不合法异常
     * @return 包含错误消息的响应内容
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, String> badRequest(IllegalArgumentException exception) {
        LOGGER.error("接口参数异常", exception);
        return createErrorResponse(exception.getMessage());
    }

    /**
     * 处理请求参数校验失败异常。
     *
     * @param exception 请求参数校验异常
     * @return 包含错误消息的响应内容
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Map<String, String> validation(MethodArgumentNotValidException exception) {
        LOGGER.error("接口参数校验异常", exception);
        String message = "请求参数不合法";
        if (exception.getBindingResult().getFieldError() != null) {
            message = exception.getBindingResult().getFieldError().getDefaultMessage();
        }
        return createErrorResponse(message);
    }

    /**
     * 处理依赖服务不可用或配置无效异常。
     *
     * @param exception 服务状态异常
     * @return 包含错误消息的响应内容
     */
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(IllegalStateException.class)
    public Map<String, String> unavailable(IllegalStateException exception) {
        LOGGER.error("接口服务状态异常", exception);
        return createErrorResponse(exception.getMessage());
    }

    /**
     * 创建统一的错误响应对象。
     *
     * @param message 错误消息
     * @return 包含错误消息的响应对象
     */
    private Map<String, String> createErrorResponse(String message) {
        Map<String, String> response = new HashMap<>();
        response.put("message", message);
        return response;
    }
}

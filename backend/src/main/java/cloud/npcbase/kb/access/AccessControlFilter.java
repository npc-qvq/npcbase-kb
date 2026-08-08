package cloud.npcbase.kb.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 默认保护全部写接口，仅放行密钥解锁和受公开额度控制的测试会话消息接口。
 *
 * @author NPC
 * @date 2026-08-06 16:01:37
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AccessControlFilter extends OncePerRequestFilter {

    /**
     * 匹配由会话控制器单独执行公开额度校验的消息发送接口。
     */
    private static final Pattern CONVERSATION_MESSAGE_PATH =
            Pattern.compile("^/api/conversations/[^/]+/messages$");

    /**
     * 唯一密钥和公开体验权限服务。
     */
    private final AccessService accessService;

    /**
     * 权限失败响应 JSON 序列化工具。
     */
    private final ObjectMapper objectMapper;

    /**
     * 创建写接口权限过滤器。
     *
     * @param accessService 唯一密钥和公开体验权限服务
     * @param objectMapper JSON 序列化工具
     */
    public AccessControlFilter(AccessService accessService, ObjectMapper objectMapper) {
        this.accessService = accessService;
        this.objectMapper = objectMapper;
    }

    /**
     * 拦截未解锁的敏感写接口，并返回统一的 KEY_REQUIRED 错误。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param filterChain 后续过滤器链
     * @throws ServletException 当后续过滤器处理失败时抛出
     * @throws IOException 当响应写入失败时抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!requiresUnlock(request) || accessService.isUnlocked(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        writeAccessDenied(response);
    }

    /**
     * 判断当前请求是否属于必须先输入密钥的敏感写操作。
     *
     * @param request 当前 HTTP 请求
     * @return 需要密钥时返回 true
     */
    private boolean requiresUnlock(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return false;
        }
        String path = request.getRequestURI();
        if ("/api/access/unlock".equals(path)) {
            return false;
        }
        return !("POST".equals(method) && CONVERSATION_MESSAGE_PATH.matcher(path).matches());
    }

    /**
     * 写入未解锁操作的统一 JSON 错误响应。
     *
     * @param response 当前 HTTP 响应
     * @throws IOException 当响应写入失败时抛出
     */
    private void writeAccessDenied(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", "KEY_REQUIRED");
        body.put("message", "请输入访问密钥后再执行此操作");
        objectMapper.writeValue(response.getWriter(), body);
    }
}

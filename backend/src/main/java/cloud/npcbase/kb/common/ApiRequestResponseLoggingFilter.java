package cloud.npcbase.kb.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 记录知识库 HTTP 接口的完整请求参数、响应内容、状态码和处理耗时。
 *
 * @author NPC
 * @date 2026-07-16 11:05:00
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiRequestResponseLoggingFilter extends OncePerRequestFilter {

    /**
     * 接口请求与响应日志记录器。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiRequestResponseLoggingFilter.class);

    /**
     * 包装 HTTP 请求和响应，记录接口完整入参与出参。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param filterChain 后续过滤器链
     * @throws ServletException 当过滤器链处理失败时抛出
     * @throws IOException 当请求或响应读写失败时抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        long startTime = System.currentTimeMillis();
        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } catch (ServletException | IOException | RuntimeException exception) {
            long elapsedTime = System.currentTimeMillis() - startTime;
            LOGGER.error("接口请求异常 method={}, uri={}, query={}, parameters={}, requestBody={}, elapsed={}ms",
                    request.getMethod(), request.getRequestURI(), request.getQueryString(),
                    getRequestParameters(requestWrapper), getRequestBody(requestWrapper), elapsedTime, exception);
            throw exception;
        } finally {
            long elapsedTime = System.currentTimeMillis() - startTime;
            String responseBody = getResponseBody(responseWrapper);
            if (responseWrapper.getStatus() >= HttpServletResponse.SC_BAD_REQUEST) {
                LOGGER.error("接口调用失败 method={}, uri={}, query={}, parameters={}, requestBody={}, status={}, responseBody={}, elapsed={}ms",
                        request.getMethod(), request.getRequestURI(), request.getQueryString(),
                        getRequestParameters(requestWrapper), getRequestBody(requestWrapper), responseWrapper.getStatus(), responseBody, elapsedTime);
            } else {
                LOGGER.info("接口调用完成 method={}, uri={}, query={}, parameters={}, requestBody={}, status={}, responseBody={}, elapsed={}ms",
                        request.getMethod(), request.getRequestURI(), request.getQueryString(),
                        getRequestParameters(requestWrapper), getRequestBody(requestWrapper), responseWrapper.getStatus(), responseBody, elapsedTime);
            }
            responseWrapper.copyBodyToResponse();
        }
    }

    /**
     * 将缓存的请求体按请求字符集转换为字符串。
     *
     * @param requestWrapper 缓存请求体的请求包装器
     * @return 请求体字符串
     */
    private String getRequestBody(ContentCachingRequestWrapper requestWrapper) {
        if ("/api/access/unlock".equals(requestWrapper.getRequestURI())) {
            return "[REDACTED]";
        }
        return new String(requestWrapper.getContentAsByteArray(), getCharset(requestWrapper.getCharacterEncoding()));
    }

    /**
     * 将请求参数转换为可读的键值日志内容。
     *
     * @param requestWrapper 缓存请求体的请求包装器
     * @return 请求参数字符串
     */
    private String getRequestParameters(ContentCachingRequestWrapper requestWrapper) {
        StringJoiner parameterJoiner = new StringJoiner(", ", "{", "}");
        for (Map.Entry<String, String[]> entry : requestWrapper.getParameterMap().entrySet()) {
            StringJoiner valueJoiner = new StringJoiner(",", "[", "]");
            for (String value : entry.getValue()) {
                valueJoiner.add(value);
            }
            parameterJoiner.add(entry.getKey() + "=" + valueJoiner);
        }
        return parameterJoiner.toString();
    }

    /**
     * 将缓存的响应体按响应内容类型和字符集转换为字符串。
     * JSON 响应按照规范固定使用 UTF-8，避免 Servlet 缺省字符集导致日志乱码。
     *
     * @param responseWrapper 缓存响应体的响应包装器
     * @return 响应体字符串
     */
    private String getResponseBody(ContentCachingResponseWrapper responseWrapper) {
        Charset charset = isJsonContentType(responseWrapper.getContentType())
                ? StandardCharsets.UTF_8
                : getCharset(responseWrapper.getCharacterEncoding());
        return new String(responseWrapper.getContentAsByteArray(), charset);
    }

    /**
     * 判断响应内容类型是否为标准 JSON 或带 JSON 后缀的媒体类型。
     *
     * @param contentType HTTP 响应内容类型
     * @return 属于 JSON 媒体类型时返回 true
     */
    private boolean isJsonContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)
                    || mediaType.getSubtype().endsWith("+json");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 解析 HTTP 消息字符集，缺省时使用 UTF-8。
     *
     * @param characterEncoding HTTP 消息声明的字符集名称
     * @return 可用于解码消息体的字符集
     */
    private Charset getCharset(String characterEncoding) {
        if (characterEncoding == null || characterEncoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        return Charset.forName(characterEncoding);
    }
}

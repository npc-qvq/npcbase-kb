package cloud.npcbase.kb.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证接口请求响应日志在 Servlet 缺省字符集下仍能正确记录 UTF-8 JSON。
 *
 * @author NPC
 * @date 2026-08-09 11:45:00
 */
@ExtendWith(OutputCaptureExtension.class)
class ApiRequestResponseLoggingFilterTest {

    /**
     * 验证未声明 charset 的 JSON 响应不会在日志中变成 Latin-1 乱码。
     *
     * @param output 测试期间捕获的日志输出
     * @throws Exception Servlet 过滤器执行失败时抛出
     */
    @Test
    void shouldLogJsonResponseUsingUtf8(CapturedOutput output) throws Exception {
        ApiRequestResponseLoggingFilter filter = new ApiRequestResponseLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String responseBody = "{\"message\":\"你好，智谱GLM\"}";

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            servletResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            servletResponse.getOutputStream().write(responseBody.getBytes(StandardCharsets.UTF_8));
        });

        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(responseBody);
        assertThat(output).contains("responseBody=" + responseBody);
        assertThat(output).doesNotContain("ä½");
    }

    @Test
    void shouldLogUtf8WhenJsonContentTypeIsMissing(CapturedOutput output) throws Exception {
        ApiRequestResponseLoggingFilter filter = new ApiRequestResponseLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String responseBody = "{\"message\":\"中文\"}";

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                servletResponse.getOutputStream().write(responseBody.getBytes(StandardCharsets.UTF_8)));

        assertThat(output).contains("responseBody=" + responseBody);
        assertThat(output).doesNotContain("ä¸­æ–‡");
    }
}

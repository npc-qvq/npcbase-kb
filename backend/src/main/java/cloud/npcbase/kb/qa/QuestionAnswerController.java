package cloud.npcbase.kb.qa;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供知识库问答请求接口。
 *
 * @author NPC
 * @date 2026-07-15 18:27:07
 */
@RestController
@RequestMapping("/api/qa")
public class QuestionAnswerController {

    /**
     * 知识库问答业务服务。
     */
    private final QuestionAnswerService questionAnswerService;

    /**
     * 创建知识库问答接口控制器。
     *
     * @param questionAnswerService 知识库问答业务服务
     */
    public QuestionAnswerController(QuestionAnswerService questionAnswerService) {
        this.questionAnswerService = questionAnswerService;
    }

    /**
     * 根据用户问题返回知识库回答及引用资料。
     *
     * @param request 知识库问答请求参数
     * @return 知识库问答结果
     */
    @PostMapping
    public QuestionAnswerService.Answer ask(@Valid @RequestBody QuestionRequest request) {
        // 调用问答服务，基于知识库资料生成回答。
        return questionAnswerService.ask(request.question());
    }

    /**
     * 表示知识库问答接口的请求参数。
     *
     * @param question 用户提交的问题文本
     * @author NPC
     * @date 2026-07-15 18:27:07
     */
    public record QuestionRequest(
            @NotBlank(message = "请输入问题")
            @Size(max = 2000, message = "问题不能超过 2000 个字符")
            String question) {
    }
}

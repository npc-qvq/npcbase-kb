package cloud.npcbase.kb.npc;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供小C启动和知识库问答接口。
 *
 * @author NPC
 * @date 2026-07-16 14:05:00
 */
@RestController
@RequestMapping("/api/npc")
public class NpcAssistantController {

    /**
     * 小C助手业务服务。
     */
    private final NpcAssistantService npcAssistantService;

    /**
     * 创建小C助手接口控制器。
     *
     * @param npcAssistantService 小C助手业务服务
     */
    public NpcAssistantController(NpcAssistantService npcAssistantService) {
        this.npcAssistantService = npcAssistantService;
    }

    /**
     * 校验启动口令并允许当前服务进程调用模型。
     *
     * @param request 小C启动请求
     * @return 小C启动处理结果
     */
    @PostMapping("/activate")
    public NpcActivationResponse activate(@RequestBody NpcActivationRequest request) {
        return npcAssistantService.activate(request == null ? null : request.command());
    }

    /**
     * 将用户问题连同知识库资料发送给已启动的小C。
     *
     * @param request 小C对话请求
     * @return 小C回答和资料引用
     */
    @PostMapping("/chat")
    public NpcChatResponse chat(@RequestBody NpcChatRequest request) {
        return npcAssistantService.chat(request);
    }
}

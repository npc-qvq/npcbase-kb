package cloud.npcbase.kb.npc;

import cloud.npcbase.kb.ai.OpenAiCompatibleClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提供小C启动、知识库问答和对话模型提供商管理接口。
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
     * OpenAI 兼容模型接口客户端。
     */
    private final OpenAiCompatibleClient aiClient;

    /**
     * 创建小C助手接口控制器。
     *
     * @param npcAssistantService 小C助手业务服务
     * @param aiClient OpenAI 兼容模型接口客户端
     */
    public NpcAssistantController(NpcAssistantService npcAssistantService, OpenAiCompatibleClient aiClient) {
        this.npcAssistantService = npcAssistantService;
        this.aiClient = aiClient;
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

    /**
     * 返回全部对话模型提供商及当前激活状态。
     *
     * @return 提供商列表和激活信息
     */
    @GetMapping("/providers")
    public Map<String, Object> providers() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeProvider", aiClient.getActiveProvider());
        result.put("activeDisplayName", aiClient.getActiveProviderDisplayName());
        result.put("providers", aiClient.listProviders());
        return result;
    }

    /**
     * 切换当前激活的对话模型提供商。
     *
     * @param name 目标提供商名称
     * @return 切换后的激活信息
     */
    @PostMapping("/provider")
    public ResponseEntity<Map<String, Object>> switchProvider(@RequestParam String name) {
        try {
            aiClient.switchProvider(name);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("activeProvider", aiClient.getActiveProvider());
            result.put("activeDisplayName", aiClient.getActiveProviderDisplayName());
            result.put("message", "已切换到" + aiClient.getActiveProviderDisplayName() + "模型");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException exception) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("message", exception.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (IllegalStateException exception) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("message", exception.getMessage());
            return ResponseEntity.status(409).body(error);
        }
    }
}

package cloud.npcbase.kb.conversation;
import cloud.npcbase.kb.access.AccessService;
import cloud.npcbase.kb.access.PublicQuotaReservation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供小C历史会话的新建、查询、对话、清空、重命名和删除接口。
 *
 * @author NPC
 * @date 2026-07-16 15:10:00
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    /**
     * 会话持久化业务服务。
     */
    private final ConversationService conversationService;

    /**
     * 唯一密钥和公开体验权限服务。
     */
    private final AccessService accessService;

    /**
     * 创建历史会话接口控制器。
     *
     * @param conversationService 会话持久化业务服务
     * @param accessService 唯一密钥和公开体验权限服务
     */
    public ConversationController(ConversationService conversationService, AccessService accessService) {
        this.conversationService = conversationService;
        this.accessService = accessService;
    }

    /**
     * 创建一条新的空会话。
     *
     * @return 新建会话摘要
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView create() {
        return conversationService.create();
    }

    /**
     * 查询左侧历史会话列表。
     *
     * @return 按最近更新时间倒序的会话摘要列表
     */
    @GetMapping
    public List<ConversationView> list() {
        return conversationService.list();
    }

    /**
     * 查询指定会话的完整消息记录。
     *
     * @param id 会话主键
     * @return 按创建时间正序的会话消息列表
     */
    @GetMapping("/{id}/messages")
    public List<ConversationMessageView> listMessages(@PathVariable String id) {
        return conversationService.listMessages(id);
    }

    /**
     * 清空指定会话的全部消息，但保留会话本身供后续继续使用。
     *
     * @param id 会话主键
     * @return 清空后的会话摘要
     */
    @DeleteMapping("/{id}/messages")
    public ConversationView clearMessages(@PathVariable String id) {
        // 删除会话消息并重置增强回答状态，使该会话恢复为空会话。
        return conversationService.clearMessages(id);
    }

    /**
     * 在指定会话中保存用户消息，并按启动状态生成系统提示或小C回答。
     *
     * @param id 会话主键
     * @param request 用户问题与小C提示词
     * @param httpRequest 当前 HTTP 请求
     * @param httpResponse 当前 HTTP 响应
     * @return 本次产生的消息和更新后的会话摘要
     */
    @PostMapping("/{id}/messages")
    public ConversationChatResponse chat(@PathVariable String id,
                                         @RequestBody ConversationMessageRequest request,
                                         HttpServletRequest httpRequest,
                                         HttpServletResponse httpResponse) {
        boolean unlocked = accessService.isUnlocked(httpRequest);
        PublicQuotaReservation reservation = null;
        if (!unlocked) {
            // 公开访客仅能为服务器配置的测试会话预占一次可用额度。
            reservation = accessService.reservePublicMessage(httpRequest, httpResponse, id);
        }
        try {
            String forcedProvider = unlocked ? null : accessService.publicProvider();
            return conversationService.chat(id, request, forcedProvider);
        } catch (RuntimeException exception) {
            // 业务或模型调用失败时归还额度，确保只有成功回答才计数。
            accessService.releasePublicMessage(reservation);
            throw exception;
        }
    }

    /**
     * 修改指定历史会话的展示名称。
     *
     * @param id 会话主键
     * @param request 新会话名称
     * @return 更新后的会话摘要
     */
    @PatchMapping("/{id}")
    public ConversationView rename(@PathVariable String id,
                                   @Valid @RequestBody ConversationRenameRequest request) {
        // 更新会话名称并返回最新摘要，使前端历史列表立即同步。
        return conversationService.rename(id, request);
    }

    /**
     * 删除指定历史会话及其全部消息。
     *
     * @param id 会话主键
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        conversationService.delete(id);
    }
}

package cloud.npcbase.kb.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供小C历史会话的新建、查询、对话和删除接口。
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
     * 创建历史会话接口控制器。
     *
     * @param conversationService 会话持久化业务服务
     */
    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
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
     * 在指定会话中保存用户消息，并按启动状态生成系统提示或小C回答。
     *
     * @param id 会话主键
     * @param request 用户问题与小C提示词
     * @return 本次产生的消息和更新后的会话摘要
     */
    @PostMapping("/{id}/messages")
    public ConversationChatResponse chat(@PathVariable String id, @RequestBody ConversationMessageRequest request) {
        return conversationService.chat(id, request);
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

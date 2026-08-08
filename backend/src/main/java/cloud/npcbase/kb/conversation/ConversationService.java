package cloud.npcbase.kb.conversation;

import cloud.npcbase.kb.common.SnowflakeIdGenerator;
import cloud.npcbase.kb.npc.NpcActivationResponse;
import cloud.npcbase.kb.npc.NpcAssistantService;
import cloud.npcbase.kb.npc.NpcChatRequest;
import cloud.npcbase.kb.npc.NpcChatResponse;
import cloud.npcbase.kb.npc.NpcCitation;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理小C会话、消息持久化和基于知识库的问答调用。
 *
 * @author NPC
 * @date 2026-07-16 15:10:00
 */
@Service
public class ConversationService {

    /**
     * 小C关闭口令的标准形式。
     */
    private static final String XIAO_C_DEACTIVATION_COMMAND = "小c关闭";

    /**
     * NPC关闭口令的兼容形式。
     */
    private static final String NPC_DEACTIVATION_COMMAND = "npc关闭";

    /**
     * 手动会话名称允许的最大字符数。
     */
    private static final int MAX_CONVERSATION_TITLE_LENGTH = 60;

    /**
     * 会话数据访问仓储。
     */
    private final ConversationRepository conversationRepository;

    /**
     * 会话消息数据访问仓储。
     */
    private final ConversationMessageRepository messageRepository;

    /**
     * 小C知识库问答服务。
     */
    private final NpcAssistantService npcAssistantService;

    /**
     * 消息引用 JSON 序列化工具。
     */
    private final ObjectMapper objectMapper;

    /**
     * 创建会话持久化服务。
     *
     * @param conversationRepository 会话数据访问仓储
     * @param messageRepository 会话消息数据访问仓储
     * @param npcAssistantService 小C知识库问答服务
     * @param objectMapper 消息引用 JSON 序列化工具
     */
    public ConversationService(ConversationRepository conversationRepository,
                               ConversationMessageRepository messageRepository,
                               NpcAssistantService npcAssistantService,
                               ObjectMapper objectMapper) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.npcAssistantService = npcAssistantService;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建一条新的空历史会话。
     *
     * @return 新建会话摘要
     */
    @Transactional
    public ConversationView create() {
        Conversation conversation = Conversation.create();
        // 持久化新会话，使页面刷新或切换设备后仍可继续对话。
        conversationRepository.insert(conversation);
        // 保存欢迎消息，确保新会话打开后可直接看到小C的启动说明。
        saveMessage(conversation.getId(), "assistant", "你好，我是小C，是属于用户NPC的私人小助理。现在可以直接提问，我会先使用本地资料模式回答；输入“小C启动”后，将启用大模型增强回答。", null);
        return toConversationView(conversation);
    }

    /**
     * 查询所有未删除会话，按最后更新时间倒序返回。
     *
     * @return 历史会话摘要列表
     */
    public List<ConversationView> list() {
        return conversationRepository.selectList(new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::isDeleted, false)
                        .orderByDesc(Conversation::getUpdatedAt))
                .stream()
                .map(this::toConversationView)
                .toList();
    }

    /**
     * 查询指定会话的全部消息。
     *
     * @param conversationId 会话主键
     * @return 按创建时间正序的消息列表
     */
    public List<ConversationMessageView> listMessages(String conversationId) {
        getActiveConversation(conversationId);
        return messageRepository.selectList(new LambdaQueryWrapper<ConversationMessage>()
                        .eq(ConversationMessage::getConversationId, conversationId)
                        .orderByAsc(ConversationMessage::getCreatedAt))
                .stream()
                .sorted(this::compareMessages)
                .map(this::toMessageView)
                .toList();
    }
    /**
     * 按创建时间和 Snowflake 主键稳定排列消息，并兼容同秒写入的旧 UUID 消息。
     *
     * @param left 左侧消息
     * @param right 右侧消息
     * @return 负数、零或正数，分别表示左侧消息在前、相同或在后
     */
    private int compareMessages(ConversationMessage left, ConversationMessage right) {
        int timeComparison = left.getCreatedAt().compareTo(right.getCreatedAt());
        if (timeComparison != 0) {
            return timeComparison;
        }
        if (SnowflakeIdGenerator.isSnowflakeId(left.getId())
                && SnowflakeIdGenerator.isSnowflakeId(right.getId())) {
            return Long.compare(SnowflakeIdGenerator.toLong(left.getId()), SnowflakeIdGenerator.toLong(right.getId()));
        }
        int roleComparison = Integer.compare(messageRoleOrder(left.getRole()), messageRoleOrder(right.getRole()));
        if (roleComparison != 0) {
            return roleComparison;
        }
        return left.getId().compareTo(right.getId());
    }

    /**
     * 为旧 UUID 消息提供同秒排序优先级，确保用户输入出现在系统或助手回复之前。
     *
     * @param role 消息角色
     * @return 角色排序优先级
     */
    private int messageRoleOrder(String role) {
        return "user".equals(role) ? 0 : 1;
    }


    /**
     * 持久化用户问题，并根据启动状态保存系统消息或小C回答。
     *
     * @param conversationId 会话主键
     * @param request 用户问题与小C提示词
     * @param forcedProvider 未解锁公开请求强制使用的提供商；解锁请求传入 null
     * @return 本次提问新增的消息和更新后的会话摘要
     */
    @Transactional
    public ConversationChatResponse chat(String conversationId, ConversationMessageRequest request, String forcedProvider) {
        Conversation conversation = getActiveConversation(conversationId);
        String question = getQuestion(request);
        List<ConversationMessageView> createdMessages = new ArrayList<>();
        // 先保存用户输入，确保模型调用失败时问题本身仍可在历史会话中追溯。
        createdMessages.add(saveMessage(conversationId, "user", question, null));
        if (isActivationCommand(question)) {
            NpcActivationResponse activation = forcedProvider == null
                    ? npcAssistantService.activate(question)
                    : npcAssistantService.activateWithProvider(question, forcedProvider);
            if (activation.active()) {
                conversation.startNpc();
                conversationRepository.updateById(conversation);
            }
            createdMessages.add(saveMessage(conversationId, "system", activation.message(), null));
            return new ConversationChatResponse(toConversationView(conversation), createdMessages);
        }
        if (isDeactivationCommand(question)) {
            NpcActivationResponse deactivation = npcAssistantService.deactivate();
            conversation.stopNpc();
            conversationRepository.updateById(conversation);
            createdMessages.add(saveMessage(conversationId, "system", deactivation.message(), null));
            return new ConversationChatResponse(toConversationView(conversation), createdMessages);
        }
        updateTitleIfNeeded(conversation, question);
        if (!conversation.isNpcStarted()) {
            // 未启动模型时直接检索本地资料，不向对话模型发起请求。
            NpcChatResponse answer = npcAssistantService.localChat(question);
            createdMessages.add(saveMessage(conversationId, "assistant", answer.answer(), serializeCitations(answer.citations())));
            conversation.touch();
            conversationRepository.updateById(conversation);
            return new ConversationChatResponse(toConversationView(conversation), createdMessages);
        }
        NpcChatResponse answer;
        if (forcedProvider == null) {
            // 服务重启后重新激活内存开关，不会向对话模型发起请求。
            npcAssistantService.activate("小c启动");
            answer = npcAssistantService.chat(new NpcChatRequest(question, request.assistantPrompt()));
        } else {
            // 公开测试会话启动后始终使用服务器指定的提供商，不能通过口令切换模型。
            answer = npcAssistantService.chatWithProvider(
                    new NpcChatRequest(question, request.assistantPrompt()), forcedProvider);
        }
        createdMessages.add(saveMessage(conversationId, "assistant", answer.answer(), serializeCitations(answer.citations())));
        conversation.touch();
        conversationRepository.updateById(conversation);
        return new ConversationChatResponse(toConversationView(conversation), createdMessages);
    }
    /**
     * 修改指定未删除会话的展示名称。
     *
     * @param conversationId 会话主键
     * @param request 新会话名称
     * @return 更新后的会话摘要
     * @throws IllegalArgumentException 当会话不存在、已删除或名称不合法时抛出
     */
    @Transactional
    public ConversationView rename(String conversationId, ConversationRenameRequest request) {
        String title = getRenameTitle(request);
        // 查询待重命名的有效会话，防止修改已删除记录。
        Conversation conversation = getActiveConversation(conversationId);
        conversation.rename(title);
        // 持久化新名称和最后更新时间，使历史列表立即反映修改。
        conversationRepository.updateById(conversation);
        return toConversationView(conversation);
    }


    /**
     * 删除指定会话及其全部消息记录。
     *
     * @param conversationId 会话主键
     */
    @Transactional
    public void delete(String conversationId) {
        Conversation conversation = getActiveConversation(conversationId);
        // 物理删除会话消息，避免已删除会话继续占用数据库空间。
        messageRepository.delete(new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId));
        // 会话主表保留逻辑删除标识，便于后续审计或恢复。
        conversation.markDeleted();
        conversationRepository.updateById(conversation);
    }

    /**
     * 查询未删除的会话实体。
     *
     * @param conversationId 会话主键
     * @return 有效会话实体
     * @throws IllegalArgumentException 当会话不存在或已删除时抛出
     */
    private Conversation getActiveConversation(String conversationId) {
        Conversation conversation = conversationRepository.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::isDeleted, false));
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在或已删除");
        }
        return conversation;
    }
    /**
     * 获取并校验用户提交的新会话名称。
     *
     * @param request 会话重命名请求
     * @return 去除首尾空格后的有效名称
     * @throws IllegalArgumentException 当名称为空或超过最大长度时抛出
     */
    private String getRenameTitle(ConversationRenameRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("请输入会话名称");
        }
        String title = request.title().trim();
        if (title.length() > MAX_CONVERSATION_TITLE_LENGTH) {
            throw new IllegalArgumentException("会话名称不能超过60个字符");
        }
        return title;
    }


    /**
     * 获取并校验用户本次提交的问题。
     *
     * @param request 会话消息请求
     * @return 去除首尾空格后的用户问题
     * @throws IllegalArgumentException 当问题为空时抛出
     */
    private String getQuestion(ConversationMessageRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("请输入问题");
        }
        return request.question().trim();
    }

    /**
     * 判断本次输入是否为小C启动口令（支持提供商参数）。
     *
     * @param question 用户输入内容
     * @return 输入为支持的启动口令时返回 true
     */
    private boolean isActivationCommand(String question) {
        return npcAssistantService.isActivationCommand(question);
    }

    /**
     * 判断本次输入是否为关闭小C大模型增强模式的口令。
     *
     * @param question 用户输入内容
     * @return 输入为支持的关闭口令时返回 true
     */
    private boolean isDeactivationCommand(String question) {
        String normalizedQuestion = question.replaceAll("\\s+", "").toLowerCase();
        return XIAO_C_DEACTIVATION_COMMAND.equals(normalizedQuestion)
                || NPC_DEACTIVATION_COMMAND.equals(normalizedQuestion);
    }

    /**
     * 在会话仍为默认标题时使用首个实际问题更新标题。
     *
     * @param conversation 当前会话
     * @param question 用户问题
     */
    private void updateTitleIfNeeded(Conversation conversation, String question) {
        if (!"新对话".equals(conversation.getTitle())) {
            return;
        }
        int titleLength = Math.min(question.length(), 30);
        String title = question.substring(0, titleLength);
        if (question.length() > titleLength) {
            title += "…";
        }
        conversation.updateTitle(title);
    }

    /**
     * 持久化单条会话消息并转换为接口展示数据。
     *
     * @param conversationId 所属会话主键
     * @param role 消息角色
     * @param content 消息正文
     * @param citationsJson 引用资料 JSON 字符串
     * @return 新增消息的展示数据
     */
    private ConversationMessageView saveMessage(String conversationId, String role, String content, String citationsJson) {
        ConversationMessage message = ConversationMessage.create(conversationId, role, content, citationsJson);
        messageRepository.insert(message);
        return toMessageView(message);
    }

    /**
     * 将小C引用资料序列化为数据库 JSON 字符串。
     *
     * @param citations 小C回答引用的资料
     * @return 可保存到数据库的 JSON 字符串
     */
    private String serializeCitations(List<NpcCitation> citations) {
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (Exception exception) {
            throw new IllegalStateException("保存回答引用失败", exception);
        }
    }

    /**
     * 将数据库 JSON 字符串转换为小C引用资料列表。
     *
     * @param citationsJson 数据库中保存的引用 JSON 字符串
     * @return 可供前端展示的资料引用列表
     */
    private List<NpcCitation> deserializeCitations(String citationsJson) {
        if (citationsJson == null || citationsJson.isBlank()) {
            return List.of();
        }
        try {
            JavaType citationListType = objectMapper.getTypeFactory().constructCollectionType(List.class, NpcCitation.class);
            return objectMapper.readValue(citationsJson, citationListType);
        } catch (Exception exception) {
            throw new IllegalStateException("读取回答引用失败", exception);
        }
    }

    /**
     * 将会话实体转换为历史列表展示数据。
     *
     * @param conversation 会话实体
     * @return 会话展示数据
     */
    private ConversationView toConversationView(Conversation conversation) {
        return new ConversationView(conversation.getId(), conversation.getTitle(), conversation.isNpcStarted(),
                conversation.getUpdatedAt());
    }

    /**
     * 将会话消息实体转换为前端展示数据。
     *
     * @param message 会话消息实体
     * @return 会话消息展示数据
     */
    private ConversationMessageView toMessageView(ConversationMessage message) {
        return new ConversationMessageView(message.getId(), message.getRole(), message.getContent(),
                deserializeCitations(message.getCitationsJson()), message.getCreatedAt());
    }
}

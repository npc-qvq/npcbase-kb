package cloud.npcbase.kb.conversation;

import cloud.npcbase.kb.npc.NpcActivationResponse;
import cloud.npcbase.kb.npc.NpcAssistantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证会话消息清空等持久化业务行为。
 *
 * @author NPC
 * @date 2026-08-09 12:10:00
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    /**
     * 会话数据访问仓储替身。
     */
    @Mock
    private ConversationRepository conversationRepository;

    /**
     * 会话消息数据访问仓储替身。
     */
    @Mock
    private ConversationMessageRepository messageRepository;

    /**
     * 小C知识库问答服务替身。
     */
    @Mock
    private NpcAssistantService npcAssistantService;

    /**
     * 验证清空消息会删除目标会话的消息，并默认启用智谱 GLM。
     */
    @Test
    void shouldClearMessagesAndEnableZhipuGlm() {
        Conversation conversation = Conversation.create();
        conversation.setId("conversation-1");
        when(conversationRepository.selectOne(any())).thenReturn(conversation);
        when(npcAssistantService.activate("小C启动 zhipu"))
                .thenReturn(new NpcActivationResponse(true, "小C 已启动（智谱GLM）", "zhipu"));
        ConversationService service = new ConversationService(
                conversationRepository, messageRepository, npcAssistantService, new ObjectMapper());

        service.clearMessages(conversation.getId());

        verify(npcAssistantService).activate("小C启动 zhipu");
        verify(messageRepository).delete(any());
        ArgumentCaptor<Conversation> conversationCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).updateById(conversationCaptor.capture());
        assertThat(conversationCaptor.getValue().isNpcStarted()).isTrue();
        assertThat(conversationCaptor.getValue().getTitle()).isEqualTo("新对话");
    }
}

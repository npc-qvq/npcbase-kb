package cloud.npcbase.kb.conversation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 使用 MyBatis-Plus 操作小C历史会话记录。
 *
 * @author NPC
 * @date 2026-07-16 15:10:00
 */
@Mapper
public interface ConversationRepository extends BaseMapper<Conversation> {
}

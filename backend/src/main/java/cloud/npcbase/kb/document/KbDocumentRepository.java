package cloud.npcbase.kb.document;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 使用 MyBatis-Plus 操作知识库文档元数据。
 *
 * @author NPC
 * @date 2026-07-16 10:09:20
 */
@Mapper
public interface KbDocumentRepository extends BaseMapper<KbDocument> {
}

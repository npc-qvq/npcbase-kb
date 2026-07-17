package cloud.npcbase.kb.ingest;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 使用 MyBatis-Plus 操作文档解析和索引任务。
 *
 * @author NPC
 * @date 2026-07-16 10:09:20
 */
@Mapper
public interface IngestTaskRepository extends BaseMapper<IngestTask> {

    /**
     * 按创建时间查询指定状态的前五条任务。
     *
     * @param status 任务状态
     * @return 待处理任务列表
     */
    @Select("SELECT * FROM kb_ingest_task WHERE status = #{status} ORDER BY created_at ASC LIMIT 5")
    List<IngestTask> findTop5ByStatusOrderByCreatedAtAsc(@Param("status") String status);

    /**
     * 删除指定文档遗留的全部解析和索引任务，适用于未配置数据库外键的场景。
     *
     * @param documentId 待清理任务所属的文档主键
     * @return 已删除的任务数量
     */
    @Delete("DELETE FROM kb_ingest_task WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") String documentId);
}

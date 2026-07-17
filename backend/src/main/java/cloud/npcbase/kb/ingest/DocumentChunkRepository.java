package cloud.npcbase.kb.ingest;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 使用 MyBatis-Plus 操作文档文本切片。
 *
 * @author NPC
 * @date 2026-07-16 10:09:20
 */
@Mapper
public interface DocumentChunkRepository extends BaseMapper<DocumentChunk> {

    /**
     * 删除指定文档的全部文本切片。
     *
     * @param documentId 文档主键
     * @return 已删除的切片数量
     */
    @Delete("DELETE FROM kb_document_chunk WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") String documentId);

    /**
     * 根据关键词查询最多十条文本切片。
     *
     * @param keyword 检索关键词
     * @return 按切片序号排序的文本切片列表
     */
    @Select("SELECT * FROM kb_document_chunk WHERE content LIKE CONCAT('%', #{keyword}, '%') ORDER BY chunk_no ASC LIMIT 10")
    List<DocumentChunk> findTop10ByContentContainingOrderByChunkNoAsc(@Param("keyword") String keyword);

    /**
     * 查询最多六条文档切片，作为关键词未命中时的问答资料上下文。
     *
     * @return 按文档和切片序号排序的文档切片列表
     */
    @Select("SELECT * FROM kb_document_chunk ORDER BY document_id ASC, chunk_no ASC LIMIT 6")
    List<DocumentChunk> findTop6ByOrderByDocumentIdAscChunkNoAsc();
}

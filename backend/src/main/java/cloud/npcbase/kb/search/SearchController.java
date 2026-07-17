package cloud.npcbase.kb.search;

import cloud.npcbase.kb.ai.OpenAiCompatibleClient;
import cloud.npcbase.kb.ai.QdrantVectorService;
import cloud.npcbase.kb.ingest.DocumentChunk;
import cloud.npcbase.kb.ingest.DocumentChunkRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供知识库关键词检索和语义检索接口。
 *
 * @author NPC
 * @date 2026-07-15 18:27:07
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    /**
     * 文档文本切片数据访问仓储。
     */
    private final DocumentChunkRepository chunkRepository;

    /**
     * OpenAI 兼容模型接口客户端。
     */
    private final OpenAiCompatibleClient aiClient;

    /**
     * Qdrant 向量检索服务。
     */
    private final QdrantVectorService vectorService;

    /**
     * 创建知识库检索接口控制器。
     *
     * @param chunkRepository 文档文本切片数据访问仓储
     * @param aiClient OpenAI 兼容模型接口客户端
     * @param vectorService Qdrant 向量检索服务
     */
    public SearchController(DocumentChunkRepository chunkRepository,
                            OpenAiCompatibleClient aiClient,
                            QdrantVectorService vectorService) {
        this.chunkRepository = chunkRepository;
        this.aiClient = aiClient;
        this.vectorService = vectorService;
    }

    /**
     * 根据关键词查询包含该关键词的文档切片。
     *
     * @param keyword 搜索关键词
     * @return 关键词检索结果列表
     * @throws IllegalArgumentException 当搜索关键词为空时抛出
     */
    @GetMapping("/keyword")
    public List<Result> keyword(@RequestParam("q") String keyword) {
        validateKeyword(keyword);
        // 查询包含关键词的前十条文档切片，并按切片序号升序排列。
        List<DocumentChunk> chunks = chunkRepository.findTop10ByContentContainingOrderByChunkNoAsc(keyword.trim());
        return chunks.stream().map(Result::from).toList();
    }

    /**
     * 根据问题文本执行语义相似度检索。
     *
     * @param keyword 用于语义检索的问题或关键词
     * @param limit 最大返回数量
     * @return 语义检索结果列表
     * @throws IllegalArgumentException 当搜索关键词为空时抛出
     * @throws IllegalStateException 当 AI 功能未启用时抛出
     */
    @GetMapping("/semantic")
    public List<SemanticResult> semantic(@RequestParam("q") String keyword,
                                         @RequestParam(defaultValue = "6") int limit) {
        validateKeyword(keyword);
        validateSemanticSearchEnabled();
        int safeLimit = Math.min(Math.max(limit, 1), 20);
        // 调用 embedding 模型生成当前查询文本的向量。
        List<Float> vector = aiClient.embed(List.of(keyword.trim())).get(0);
        // 在 Qdrant 中检索与查询向量最相近的文档切片。
        List<QdrantVectorService.Match> matches = vectorService.search(vector, safeLimit);
        return matches.stream().map(SemanticResult::from).toList();
    }

    /**
     * 校验搜索关键词。
     *
     * @param keyword 搜索关键词
     * @throws IllegalArgumentException 当搜索关键词为空时抛出
     */
    private void validateKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入搜索关键词");
        }
    }

    /**
     * 校验语义检索所需的 AI 功能是否已启用。
     *
     * @throws IllegalStateException 当 AI 功能未启用时抛出
     */
    private void validateSemanticSearchEnabled() {
        if (!vectorService.enabled()) {
            throw new IllegalStateException("AI 功能尚未启用，无法进行语义检索");
        }
    }

    /**
     * 表示一次关键词检索命中的文档切片摘要。
     *
     * @param chunkId 文档切片主键
     * @param documentId 文档主键
     * @param chunkNo 文档内切片序号
     * @param excerpt 切片内容摘要
     * @author NPC
     * @date 2026-07-15 18:27:07
     */
    public record Result(String chunkId, String documentId, int chunkNo, String excerpt) {

        /**
         * 将文档切片实体转换为关键词检索结果。
         *
         * @param chunk 文档切片实体
         * @return 关键词检索结果
         */
        private static Result from(DocumentChunk chunk) {
            String text = chunk.getContent();
            String excerpt = text.substring(0, Math.min(text.length(), 280));
            return new Result(chunk.getId(), chunk.getDocumentId(), chunk.getChunkNo(), excerpt);
        }
    }

    /**
     * 表示一次语义检索命中的文档切片摘要和相似度得分。
     *
     * @param chunkId 文档切片主键
     * @param documentId 文档主键
     * @param chunkNo 文档内切片序号
     * @param excerpt 切片内容摘要
     * @param score 向量相似度得分
     * @author NPC
     * @date 2026-07-15 18:27:07
     */
    public record SemanticResult(String chunkId, String documentId, int chunkNo, String excerpt, double score) {

        /**
         * 将 Qdrant 检索结果转换为语义检索接口结果。
         *
         * @param match Qdrant 检索匹配结果
         * @return 语义检索接口结果
         */
        private static SemanticResult from(QdrantVectorService.Match match) {
            String text = match.getText();
            String excerpt = text.substring(0, Math.min(text.length(), 400));
            return new SemanticResult(match.getChunkId(), match.getDocumentId(), match.getChunkNo(), excerpt, match.getScore());
        }
    }
}

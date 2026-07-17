package cloud.npcbase.kb.qa;

import cloud.npcbase.kb.ai.OpenAiCompatibleClient;
import cloud.npcbase.kb.ai.QdrantVectorService;
import cloud.npcbase.kb.document.KbDocument;
import cloud.npcbase.kb.document.KbDocumentRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 根据知识库检索结果生成带引用来源的问答结果。
 *
 * @author NPC
 * @date 2026-07-15 18:27:07
 */
@Service
public class QuestionAnswerService {

    /**
     * 约束模型仅基于检索资料回答的系统提示词。
     */
    private static final String SYSTEM_PROMPT = "你是个人知识库助手。只能基于给出的资料回答，不要使用外部知识或编造内容。"
            + "如果资料不足，请明确回答“资料中没有足够信息回答这个问题”。回答使用中文，并在每个关键结论后以 [序号] 标注资料来源。";

    /**
     * OpenAI 兼容模型接口客户端。
     */
    private final OpenAiCompatibleClient aiClient;

    /**
     * Qdrant 向量检索服务。
     */
    private final QdrantVectorService vectorService;

    /**
     * 知识库文档数据访问仓储。
     */
    private final KbDocumentRepository documentRepository;

    /**
     * 创建知识库问答服务。
     *
     * @param aiClient OpenAI 兼容模型接口客户端
     * @param vectorService Qdrant 向量检索服务
     * @param documentRepository 知识库文档数据访问仓储
     */
    public QuestionAnswerService(OpenAiCompatibleClient aiClient,
                                 QdrantVectorService vectorService,
                                 KbDocumentRepository documentRepository) {
        this.aiClient = aiClient;
        this.vectorService = vectorService;
        this.documentRepository = documentRepository;
    }

    /**
     * 根据用户问题检索资料并生成带来源引用的回答。
     *
     * @param question 用户问题
     * @return 问答结果及引用资料列表
     * @throws IllegalArgumentException 当用户问题为空时抛出
     * @throws IllegalStateException 当 AI 功能未启用时抛出
     */
    public Answer ask(String question) {
        validateQuestion(question);
        validateAiEnabled();
        // 调用 embedding 模型生成用户问题的查询向量。
        List<Float> questionVector = aiClient.embed(List.of(question.trim())).get(0);
        // 在向量库中查询与问题最相关的六段资料。
        List<QdrantVectorService.Match> matches = vectorService.search(questionVector, 6);
        if (matches.isEmpty()) {
            return new Answer("资料中没有足够信息回答这个问题。", new ArrayList<>());
        }
        // 查询引用切片所属的文档元数据，用于展示来源标题。
        Map<String, KbDocument> documentMap = findDocumentsByMatches(matches);
        List<Citation> citations = new ArrayList<>();
        String context = buildContext(matches, documentMap, citations);
        String prompt = buildQuestionPrompt(question, context);
        // 调用对话模型生成仅基于检索资料的回答。
        String answer = aiClient.chat(SYSTEM_PROMPT, prompt);
        return new Answer(answer, citations);
    }

    /**
     * 校验用户问题。
     *
     * @param question 用户问题
     * @throws IllegalArgumentException 当用户问题为空时抛出
     */
    private void validateQuestion(String question) {
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入问题");
        }
    }

    /**
     * 校验 AI 功能是否启用。
     *
     * @throws IllegalStateException 当 AI 功能未启用时抛出
     */
    private void validateAiEnabled() {
        if (!vectorService.enabled()) {
            throw new IllegalStateException("语义检索尚未启用，请先配置硅基流动 BGE-M3 和 Qdrant");
        }
    }

    /**
     * 查询语义检索结果关联的文档，并以文档主键为键组织返回结果。
     *
     * @param matches Qdrant 语义检索匹配结果
     * @return 文档主键到文档实体的映射
     */
    private Map<String, KbDocument> findDocumentsByMatches(List<QdrantVectorService.Match> matches) {
        List<String> documentIds = matches.stream().map(QdrantVectorService.Match::getDocumentId).toList();
        // 根据检索命中的文档主键批量查询文档元数据。
        return documentRepository.selectByIds(documentIds).stream()
                .collect(Collectors.toMap(KbDocument::getId, document -> document));
    }

    /**
     * 拼接模型回答所需的资料上下文，并同步构造引用列表。
     *
     * @param matches Qdrant 语义检索匹配结果
     * @param documentMap 文档主键到文档实体的映射
     * @param citations 待填充的引用资料列表
     * @return 供模型阅读的资料上下文文本
     */
    private String buildContext(List<QdrantVectorService.Match> matches,
                                Map<String, KbDocument> documentMap,
                                List<Citation> citations) {
        StringBuilder context = new StringBuilder();
        for (int index = 0; index < matches.size(); index++) {
            QdrantVectorService.Match match = matches.get(index);
            KbDocument document = documentMap.get(match.getDocumentId());
            String title = document == null ? "已删除文档" : document.getTitle();
            int citationNumber = index + 1;
            context.append("[资料 ").append(citationNumber).append("] 文档：").append(title).append("；切片 ")
                    .append(match.getChunkNo()).append("\n").append(match.getText()).append("\n\n");
            citations.add(new Citation(citationNumber, match.getDocumentId(), title, match.getChunkNo(),
                    createExcerpt(match.getText()), match.getScore()));
        }
        return context.toString();
    }

    /**
     * 拼接模型问答提示词。
     *
     * @param question 用户问题
     * @param context 检索得到的资料上下文
     * @return 对话模型的用户提示词
     */
    private String buildQuestionPrompt(String question, String context) {
        return "问题：\n" + question.trim() + "\n\n可用资料：\n" + context + "请仅依据可用资料作答，并用 [1]、[2] 这类编号引用。";
    }

    /**
     * 生成引用资料展示用的文本摘要。
     *
     * @param text 原始切片文本
     * @return 最多四百字符的文本摘要
     */
    private String createExcerpt(String text) {
        return text.substring(0, Math.min(text.length(), 400));
    }

    /**
     * 表示知识库问答接口的回答结果。
     *
     * @param answer 基于检索资料生成的回答
     * @param citations 回答引用的资料列表
     * @author NPC
     * @date 2026-07-15 18:27:07
     */
    public record Answer(String answer, List<Citation> citations) {
    }

    /**
     * 表示回答中一条可追溯的文档切片引用。
     *
     * @param number 引用序号
     * @param documentId 文档主键
     * @param documentTitle 文档标题
     * @param chunkNo 文档内切片序号
     * @param excerpt 切片文本摘要
     * @param score 向量相似度得分
     * @author NPC
     * @date 2026-07-15 18:27:07
     */
    public record Citation(int number, String documentId, String documentTitle, int chunkNo, String excerpt, double score) {
    }
}

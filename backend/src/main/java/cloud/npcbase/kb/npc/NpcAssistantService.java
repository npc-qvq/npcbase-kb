package cloud.npcbase.kb.npc;

import cloud.npcbase.kb.ai.OpenAiCompatibleClient;
import cloud.npcbase.kb.ai.QdrantVectorService;
import cloud.npcbase.kb.document.KbDocument;
import cloud.npcbase.kb.document.KbDocumentRepository;
import cloud.npcbase.kb.ingest.DocumentChunk;
import cloud.npcbase.kb.ingest.DocumentChunkRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 管理小C启动状态，并在启动后基于知识库资料调用模型生成回答。
 *
 * @author NPC
 * @date 2026-07-16 14:05:00
 */
@Service
public class NpcAssistantService {

    /**
     * 小C启动口令去除空白后的标准前缀。
     */
    private static final String XIAO_C_ACTIVATION_PREFIX = "小c启动";

    /**
     * NPC启动口令去除空白后的标准前缀。
     */
    private static final String NPC_ACTIVATION_PREFIX = "npc启动";

    /**
     * 从本地资料中提取 HTTP 地址的正则表达式。
     */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s，。；、]+", Pattern.CASE_INSENSITIVE);

    /**
     * OpenAI 兼容模型接口客户端。
     */
    private final OpenAiCompatibleClient aiClient;

    /**
     * 提供 BGE-M3 向量相似度检索能力的 Qdrant 服务。
     */
    private final QdrantVectorService vectorService;

    /**
     * 知识库文本切片数据访问仓储。
     */
    private final DocumentChunkRepository chunkRepository;

    /**
     * 知识库文档数据访问仓储。
     */
    private final KbDocumentRepository documentRepository;

    /**
     * 当前服务进程中小C是否已启动。
     */
    private final AtomicBoolean active = new AtomicBoolean(false);

    /**
     * 创建小C助手服务。
     *
     * @param aiClient OpenAI 兼容模型接口客户端
     * @param vectorService Qdrant 向量检索服务
     * @param chunkRepository 知识库文本切片数据访问仓储
     * @param documentRepository 知识库文档数据访问仓储
     */
    public NpcAssistantService(OpenAiCompatibleClient aiClient,
                               QdrantVectorService vectorService,
                               DocumentChunkRepository chunkRepository,
                               KbDocumentRepository documentRepository) {
        this.aiClient = aiClient;
        this.vectorService = vectorService;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
    }

    /**
     * 校验启动口令，并在口令正确后允许当前进程调用模型。
     *
     * <p>支持在口令后追加提供商名称来切换对话模型：
     * <ul>
     *   <li>"小c启动" —— 使用当前激活的提供商</li>
     *   <li>"小c启动 智谱" / "小c启动 zhipu" —— 切换到智谱后启动</li>
     *   <li>"小c启动 deepseek" —— 切换到 DeepSeek 后启动</li>
     *   <li>"npc启动" —— 向后兼容，使用当前激活的提供商</li>
     * </ul>
     *
     * @param command 用户输入的启动口令
     * @return 小C启动处理结果
     */
    public NpcActivationResponse activate(String command) {
        String normalized = normalizeCommand(command);
        if (!isActivationCommand(normalized)) {
            return new NpcActivationResponse(false, "请输入“小c启动”后再调用小C。", null);
        }
        String providerName = extractProviderFromCommand(normalized);
        if (providerName != null) {
            try {
                aiClient.switchProvider(providerName);
            } catch (Exception exception) {
                return new NpcActivationResponse(false, exception.getMessage(), null);
            }
        }
        if (!aiClient.chatEnabled()) {
            String displayName = aiClient.getActiveProviderDisplayName();
            return new NpcActivationResponse(false, displayName + " 对话服务尚未配置完成，当前仍可使用资料检索模式。",
                    aiClient.getActiveProvider());
        }
        active.set(true);
        String displayName = aiClient.getActiveProviderDisplayName();
        return new NpcActivationResponse(true, "小C 已启动（" + displayName + "），现在可以基于知识库资料回答问题。",
                aiClient.getActiveProvider());
    }

    /**
     * 返回当前服务进程中小C是否已经启动。
     *
     * @return 小C已启动时返回 true
     */
    public boolean isActive() {
        return active.get();
    }

    /**
     * 关闭当前服务进程中的对话模型开关，使后续会话回到资料检索模式。
     *
     * @return 小C关闭后的处理结果
     */
    public NpcActivationResponse deactivate() {
        active.set(false);
        return new NpcActivationResponse(false, "小C 已关闭，后续提问将使用资料检索模式，不调用大模型。",
                aiClient.getActiveProvider());
    }

    /**
     * 判断用户输入是否为允许启动小C的口令。
     *
     * <p>精确匹配 "小c启动" 或 "npc启动" 时返回 true；当口令后追加可解析的
     * 提供商名称时也返回 true；追加的内容无法解析为提供商时返回 false，
     * 使该输入作为普通问题处理。
     *
     * @param command 用户输入的启动口令
     * @return 输入为小C或NPC启动口令时返回 true
     */
    public boolean isActivationCommand(String command) {
        String normalized = normalizeCommand(command);
        return isNormalizedActivationCommand(normalized);
    }

    /**
     * 根据用户问题收集知识库资料，并调用模型生成小C回答。
     *
     * @param request 小C对话请求
     * @return 小C回答和关联资料来源
     * @throws IllegalArgumentException 当问题为空时抛出
     * @throws IllegalStateException 当小C未启动或模型未配置时抛出
     */
    public NpcChatResponse chat(NpcChatRequest request) {
        validateActive();
        String question = request == null ? null : request.question();
        validateQuestion(question);
        List<DocumentChunk> chunks = findRelevantChunks(question);
        List<NpcCitation> citations = createCitations(chunks);
        String answer = aiClient.chat(buildSystemPrompt(request.assistantPrompt()), buildUserPrompt(question, chunks, citations));
        return new NpcChatResponse(answer, citations);
    }

    /**
     * 不连接模型服务，直接返回本地知识库中命中的资料摘要和引用。
     *
     * @param question 用户问题
     * @return 本地资料模式的回答和资料引用
     * @throws IllegalArgumentException 当用户问题为空时抛出
     */
    public NpcChatResponse localChat(String question) {
        validateQuestion(question);
        List<DocumentChunk> chunks = findRelevantChunks(question);
        List<NpcCitation> citations = createCitations(chunks);
        return new NpcChatResponse(buildLocalAnswer(chunks, citations), citations);
    }

    /**
     * 将用户输入转为可比较的启动口令。
     *
     * @param command 用户输入的原始口令
     * @return 小写且移除空白后的口令
     */
    private String normalizeCommand(String command) {
        if (command == null) {
            return "";
        }
        return command.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 确认小C已经通过启动口令开启。
     *
     * @throws IllegalStateException 当小C尚未启动时抛出
     */
    private void validateActive() {
        if (!active.get()) {
            throw new IllegalStateException("小C尚未启动，请先输入“小c启动”。");
        }
    }

    /**
     * 判断已规范化的口令是否为启动小C的指令。
     *
     * @param normalized 已去除空白并小写的口令
     * @return 精确匹配或带有可解析提供商后缀时返回 true
     */
    private boolean isNormalizedActivationCommand(String normalized) {
        if (XIAO_C_ACTIVATION_PREFIX.equals(normalized) || NPC_ACTIVATION_PREFIX.equals(normalized)) {
            return true;
        }
        return extractProviderFromCommand(normalized) != null;
    }

    /**
     * 从已规范化的口令中提取提供商名称。
     *
     * @param normalized 已去除空白并小写的口令
     * @return 解析到的标准提供商名称；无后缀或无法解析时返回 null
     */
    private String extractProviderFromCommand(String normalized) {
        String remainder = null;
        if (normalized.startsWith(XIAO_C_ACTIVATION_PREFIX)) {
            remainder = normalized.substring(XIAO_C_ACTIVATION_PREFIX.length());
        } else if (normalized.startsWith(NPC_ACTIVATION_PREFIX)) {
            remainder = normalized.substring(NPC_ACTIVATION_PREFIX.length());
        }
        if (remainder == null || remainder.isEmpty()) {
            return null;
        }
        return aiClient.resolveProviderName(remainder);
    }

    /**
     * 校验用户问题是否有效。
     *
     * @param question 用户问题
     * @throws IllegalArgumentException 当问题为空时抛出
     */
    private void validateQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("请输入问题");
        }
    }

    /**
     * 优先使用 BGE-M3 和 Qdrant 进行语义检索，未启用或无结果时再回退到关键词检索。
     *
     * @param question 用户问题
     * @return 可供小C参考的资料切片
     */
    private List<DocumentChunk> findRelevantChunks(String question) {
        if (vectorService.enabled()) {
            List<DocumentChunk> semanticChunks = findSemanticChunks(question);
            if (!semanticChunks.isEmpty()) {
                return semanticChunks;
            }
        }
        // 优先使用用户问题进行关键词检索，减少无关资料进入模型上下文。
        List<DocumentChunk> chunks = chunkRepository.findTop10ByContentContainingOrderByChunkNoAsc(question.trim());
        if (!chunks.isEmpty()) {
            return chunks;
        }
        List<String> keywords = extractSearchKeywords(question);
        Map<String, DocumentChunk> matchedChunks = new LinkedHashMap<>();
        for (String keyword : keywords) {
            // 使用问题中的连续关键词逐个查询，支持"武汉太康""卫生专网"等本地精确命中。
            List<DocumentChunk> keywordChunks = chunkRepository.findTop10ByContentContainingOrderByChunkNoAsc(keyword);
            for (DocumentChunk chunk : keywordChunks) {
                matchedChunks.putIfAbsent(chunk.getId(), chunk);
            }
        }
        if (!matchedChunks.isEmpty()) {
            return matchedChunks.values().stream()
                    .sorted(Comparator.comparingInt((DocumentChunk chunk) -> calculateMatchScore(chunk, keywords)).reversed()
                            .thenComparing(DocumentChunk::getChunkNo))
                    .limit(6)
                    .toList();
        }
        // 未直接命中时提供少量已归档资料，使小C仍能识别当前知识库内容。
        return chunkRepository.findTop6ByOrderByDocumentIdAscChunkNoAsc();
    }

    /**
     * 为当前问题生成 BGE-M3 向量，从 Qdrant 召回候选资料后使用重排模型保留最相关切片。
     *
     * @param question 用户问题
     * @return 按重排相关度排序的文档切片列表，未命中时返回空列表
     */
    private List<DocumentChunk> findSemanticChunks(String question) {
        // 调用硅基流动将用户问题转换为与文档同维度的 BGE-M3 查询向量。
        List<Float> questionVector = aiClient.embed(List.of(question.trim())).get(0);
        // 在 Qdrant 中先召回二十个语义接近的候选切片，为重排模型提供足够的筛选空间。
        List<QdrantVectorService.Match> matches = vectorService.search(questionVector, 20);
        List<DocumentChunk> chunks = new ArrayList<>();
        for (QdrantVectorService.Match match : matches) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(match.getChunkId());
            chunk.setDocumentId(match.getDocumentId());
            chunk.setChunkNo(match.getChunkNo());
            chunk.setContent(match.getText());
            chunks.add(chunk);
        }
        return rerankSemanticChunks(question, chunks);
    }

    /**
     * 使用硅基流动重排模型压低 SQL 等噪声切片，仅保留与当前问题最相关的资料。
     *
     * @param question 用户问题
     * @param chunks Qdrant 语义召回的候选切片
     * @return 重排后需要用于回答的切片列表
     */
    private List<DocumentChunk> rerankSemanticChunks(String question, List<DocumentChunk> chunks) {
        if (chunks.isEmpty() || !aiClient.rerankEnabled()) {
            return chunks;
        }
        List<String> contents = chunks.stream().map(DocumentChunk::getContent).toList();
        int topN = Math.min(aiClient.rerankTopN(), chunks.size());
        // 将候选切片交给 BGE reranker 精排，避免仅依赖向量相似度引入低质量 SQL 文本。
        List<Integer> indexes = aiClient.rerank(question, contents, topN);
        List<DocumentChunk> rerankedChunks = new ArrayList<>();
        for (Integer index : indexes) {
            rerankedChunks.add(chunks.get(index));
        }
        return rerankedChunks;
    }

    /**
     * 从问题中提取适合数据库 LIKE 查询的连续关键词。
     *
     * @param question 用户问题
     * @return 按长度倒序排列的关键词列表
     */
    private List<String> extractSearchKeywords(String question) {
        String compactQuestion = question.replaceAll("请问|帮我|一下|可以|能否|有没有|是什么|什么|多少|相关|地址|的|是|吗|\\s+", "");
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        for (int length = Math.min(6, compactQuestion.length()); length >= 2; length--) {
            for (int start = 0; start <= compactQuestion.length() - length; start++) {
                keywords.add(compactQuestion.substring(start, start + length));
                if (keywords.size() >= 20) {
                    return new ArrayList<>(keywords);
                }
            }
        }
        return new ArrayList<>(keywords);
    }

    /**
     * 计算一个资料切片与当前关键词集合的匹配度。
     *
     * @param chunk 候选资料切片
     * @param keywords 用户问题提取出的关键词
     * @return 匹配度分值
     */
    private int calculateMatchScore(DocumentChunk chunk, List<String> keywords) {
        int score = 0;
        for (String keyword : keywords) {
            if (chunk.getContent().contains(keyword)) {
                score += keyword.length() * keyword.length();
            }
        }
        return score;
    }

    /**
     * 根据资料切片查询文档标题，并转换为前端可展示的引用。
     *
     * @param chunks 用于回答的资料切片
     * @return 资料引用列表
     */
    private List<NpcCitation> createCitations(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> documentIds = chunks.stream().map(DocumentChunk::getDocumentId).distinct().toList();
        // 批量查询切片所属文档，确保资料归档标题可展示在回答下方。
        Map<String, KbDocument> documents = documentRepository.selectByIds(documentIds).stream()
                .collect(Collectors.toMap(KbDocument::getId, document -> document));
        List<NpcCitation> citations = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            KbDocument document = documents.get(chunk.getDocumentId());
            String title = document == null ? "未知资料" : document.getTitle();
            citations.add(new NpcCitation(chunk.getDocumentId(), title, chunk.getChunkNo(), abbreviate(chunk.getContent())));
        }
        return citations;
    }

    /**
     * 根据当前激活的对话模型动态生成默认角色提示词。
     *
     * @return 包含当前模型名称的系统提示词
     */
    private String buildDefaultSystemPrompt() {
        String displayName = aiClient.getActiveProviderDisplayName();
        return "你叫小C，是 NPC 的个人知识库助手，使用 " + displayName + " 模型提供回答。"
                + "请优先依据下方提供的资料回答，不能从资料中确定时要明确说明。回答使用中文，表达清晰、友好、简洁。";
    }

    /**
     * 组合默认角色提示词和用户在页面设置的补充提示词。
     *
     * @param assistantPrompt 用户补充的小C角色提示词
     * @return 发送给模型的系统提示词
     */
    private String buildSystemPrompt(String assistantPrompt) {
        String systemPrompt = buildDefaultSystemPrompt();
        if (assistantPrompt == null || assistantPrompt.isBlank()) {
            return systemPrompt;
        }
        return systemPrompt + "\n\n用户对小C的额外设定：\n" + assistantPrompt.trim();
    }

    /**
     * 将问题和可用资料拼接为模型用户消息。
     *
     * @param question 用户问题
     * @param chunks 可用资料切片
     * @param citations 切片对应的引用资料
     * @return 发送给模型的用户消息
     */
    private String buildUserPrompt(String question, List<DocumentChunk> chunks, List<NpcCitation> citations) {
        if (chunks.isEmpty()) {
            return "用户问题：\n" + question.trim() + "\n\n当前知识库中尚未归档资料。请如实告知用户。";
        }
        StringBuilder prompt = new StringBuilder("用户问题：\n").append(question.trim()).append("\n\n可用知识库资料：\n");
        for (int index = 0; index < chunks.size(); index++) {
            prompt.append("[资料 ").append(index + 1).append("] ")
                    .append(citations.get(index).documentTitle()).append("，切片 ")
                    .append(chunks.get(index).getChunkNo()).append("\n")
                    .append(chunks.get(index).getContent()).append("\n\n");
        }
        prompt.append("请仅基于以上资料回答；资料不足时请明确说明。");
        return prompt.toString();
    }

    /**
     * 生成不依赖外部模型的本地资料检索回答。
     *
     * @param citations 本地检索命中的资料引用
     * @return 包含资料摘要的本地模式回答
     */
    private String buildLocalAnswer(List<DocumentChunk> chunks, List<NpcCitation> citations) {
        if (citations.isEmpty()) {
            return "当前知识库还没有可用资料。请先在右侧上传并等待资料解析完成。";
        }
        List<String> urls = findUrls(chunks);
        StringBuilder answer = new StringBuilder("当前为资料检索模式，未连接大模型。\n\n");
        if (!urls.isEmpty()) {
            answer.append("从命中资料中提取到以下地址：\n");
            for (String url : urls) {
                answer.append("- ").append(url).append("\n");
            }
            answer.append("\n");
        }
        answer.append("找到以下相关资料：\n\n");
        for (int index = 0; index < citations.size(); index++) {
            NpcCitation citation = citations.get(index);
            answer.append(index + 1).append(". ").append(citation.documentTitle())
                    .append("（切片 ").append(citation.chunkNo()).append("）\n")
                    .append(abbreviate(citation.excerpt())).append("\n\n");
        }
        answer.append("如需小C整理、归纳或生成更自然的回答，请输入“小c启动”启用大模型增强。\n");
        return answer.toString();
    }

    /**
     * 从命中的资料切片中提取不重复的 HTTP 地址。
     *
     * @param chunks 命中的资料切片
     * @return 文本中出现的地址列表
     */
    private List<String> findUrls(List<DocumentChunk> chunks) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (DocumentChunk chunk : chunks) {
            Matcher matcher = URL_PATTERN.matcher(chunk.getContent());
            while (matcher.find()) {
                urls.add(matcher.group());
            }
        }
        return new ArrayList<>(urls);
    }

    /**
     * 截取适合前端展示的资料摘要。
     *
     * @param text 原始资料文本
     * @return 最多四百字符的资料摘要
     */
    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.substring(0, Math.min(text.length(), 400));
    }
}

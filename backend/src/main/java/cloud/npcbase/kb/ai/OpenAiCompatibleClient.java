package cloud.npcbase.kb.ai;

import cloud.npcbase.kb.config.ChatProperties;
import cloud.npcbase.kb.config.EmbeddingProperties;
import cloud.npcbase.kb.config.KbProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分别调用对话模型和向量模型的 OpenAI 兼容接口，避免 DeepSeek 与硅基流动共用配置。
 *
 * @author NPC
 * @date 2026-07-16 16:23:00
 */
@Component
public class OpenAiCompatibleClient {

    /**
     * 提供聊天、向量模型及其密钥的知识库服务配置。
     */
    private final KbProperties properties;

    /**
     * 负责序列化请求体和反序列化模型响应的 JSON 工具。
     */
    private final ObjectMapper objectMapper;

    /**
     * 对外调用 DeepSeek 和硅基流动接口的 HTTP 客户端。
     */
    private final HttpClient httpClient;

    /**
     * 创建 OpenAI 兼容模型服务客户端。
     *
     * @param properties 知识库模型服务配置
     * @param objectMapper JSON 序列化和反序列化工具
     */
    public OpenAiCompatibleClient(KbProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * 将文本按硅基流动请求上限分批转换为 BGE-M3 向量。
     *
     * @param texts 待向量化的文档切片或查询文本
     * @return 与输入文本顺序一一对应的浮点向量列表
     */
    public List<List<Float>> embed(List<String> texts) {
        validateEmbeddingEnabled();
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        EmbeddingProperties embedding = properties.getEmbedding();
        int batchSize = Math.max(1, embedding.getBatchSize());
        List<List<Float>> vectors = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            List<String> batch = texts.subList(start, end);
            Map<String, Object> requestBody = Map.of("model", requireValue(embedding.getModel(), "KB_EMBEDDING_MODEL"),
                    "input", batch);
            // 将本批次文档切片发送到硅基流动，生成可写入 Qdrant 的语义向量。
            JsonNode response = post(embedding.getBaseUrl(), embedding.getApiKey(), "embeddings", requestBody);
            List<List<Float>> batchVectors = readVectors(response);
            if (batchVectors.size() != batch.size()) {
                throw new IllegalStateException("硅基流动返回的向量数量与输入文本数量不一致");
            }
            vectors.addAll(batchVectors);
        }
        return vectors;
    }

    /**
     * 使用已配置的 DeepSeek 对话模型，根据系统提示词和用户上下文生成回答。
     *
     * @param systemPrompt 约束小C角色与回答范围的系统提示词
     * @param userPrompt 用户问题及检索出的知识库上下文
     * @return DeepSeek 生成的回答文本
     */
    public String chat(String systemPrompt, String userPrompt) {
        validateChatEnabled();
        ChatProperties chat = properties.getChat();
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt));
        Map<String, Object> requestBody = Map.of("model", requireValue(chat.getModel(), "KB_CHAT_MODEL"),
                "temperature", 0.2,
                "messages", messages);
        // 仅在小C已被启动后由上层服务调用 DeepSeek 生成自然语言回答。
        JsonNode response = post(chat.getBaseUrl(), chat.getApiKey(), "chat/completions", requestBody);
        String answer = response.path("choices").path(0).path("message").path("content").asText();
        if (answer == null || answer.trim().isEmpty()) {
            throw new IllegalStateException("DeepSeek 未返回回答内容");
        }
        return answer.trim();
    }

    /**
     * 判断 DeepSeek 对话能力是否完成启用和基础模型配置。
     *
     * @return 对话模型可调用时返回 true
     */
    public boolean chatEnabled() {
        ChatProperties chat = properties.getChat();
        return chat.isEnabled() && hasText(chat.getBaseUrl()) && hasText(chat.getApiKey()) && hasText(chat.getModel());
    }

    /**
     * 判断硅基流动 BGE-M3 向量能力是否完成启用和基础模型配置。
     *
     * @return 向量模型可调用时返回 true
     */
    public boolean embeddingEnabled() {
        EmbeddingProperties embedding = properties.getEmbedding();
        return embedding.isEnabled() && hasText(embedding.getBaseUrl()) && hasText(embedding.getApiKey())
                && hasText(embedding.getModel());
    }

    /**
     * 使用硅基流动重排模型，对 Qdrant 召回的候选切片重新计算与问题的相关性。
     *
     * @param query 用户提出的问题
     * @param documents 需要重新排序的候选切片文本
     * @param topN 最终保留的候选切片数量
     * @return 按相关性降序排列的候选切片下标列表
     */
    public List<Integer> rerank(String query, List<String> documents, int topN) {
        validateRerankEnabled();
        if (documents == null || documents.isEmpty()) {
            return new ArrayList<>();
        }
        EmbeddingProperties embedding = properties.getEmbedding();
        int safeTopN = Math.min(Math.max(topN, 1), documents.size());
        Map<String, Object> requestBody = Map.of("model", requireValue(embedding.getRerankModel(), "KB_RERANK_MODEL"),
                "query", requireValue(query, "用户问题"),
                "documents", documents,
                "top_n", safeTopN,
                "return_documents", true);
        // 将 Qdrant 语义召回的候选切片交给重排模型，过滤相似但无关的 SQL 等文本。
        JsonNode response = post(embedding.getBaseUrl(), embedding.getApiKey(), "rerank", requestBody);
        return readRerankedIndexes(response, documents.size());
    }

    /**
     * 判断硅基流动重排能力是否具备调用所需的开关和模型配置。
     *
     * @return 重排模型可调用时返回 true
     */
    public boolean rerankEnabled() {
        EmbeddingProperties embedding = properties.getEmbedding();
        return embeddingEnabled() && embedding.isRerankEnabled() && hasText(embedding.getRerankModel());
    }

    /**
     * 返回当前配置允许重排后保留的最大切片数量。
     *
     * @return 至少为一的重排结果数量
     */
    public int rerankTopN() {
        return Math.max(1, properties.getEmbedding().getRerankTopN());
    }

    /**
     * 向指定 OpenAI 兼容服务发出 JSON POST 请求。
     *
     * @param baseUrl 服务基础地址
     * @param apiKey 服务访问密钥
     * @param path 服务基础地址后的接口路径
     * @param requestBody 请求体
     * @return 解析后的 JSON 响应
     */
    private JsonNode post(String baseUrl, String apiKey, String path, Object requestBody) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(trimTrailingSlash(requireValue(baseUrl, "服务地址")) + "/" + path))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)));
            if (hasText(apiKey)) {
                builder.header("Authorization", "Bearer " + apiKey.trim());
            }
            // 向当前模型服务提交请求并读取完整 JSON 响应，供调用方解析结果。
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            validateResponse(response);
            return objectMapper.readTree(response.body());
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("调用模型服务失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 从 OpenAI 兼容 embeddings 响应读取并按 index 还原向量顺序。
     *
     * @param response 向量接口 JSON 响应
     * @return 与请求输入顺序相同的文本向量列表
     */
    private List<List<Float>> readVectors(JsonNode response) {
        List<JsonNode> data = new ArrayList<>();
        response.path("data").forEach(data::add);
        data.sort(Comparator.comparingInt(item -> item.path("index").asInt()));
        List<List<Float>> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            List<Float> vector = new ArrayList<>();
            item.path("embedding").forEach(value -> vector.add((float) value.asDouble()));
            if (vector.isEmpty()) {
                throw new IllegalStateException("硅基流动返回了空向量");
            }
            vectors.add(vector);
        }
        return vectors;
    }

    /**
     * 从硅基流动重排响应中读取原候选切片的下标，并过滤重复或越界结果。
     *
     * @param response 重排接口 JSON 响应
     * @param documentCount 原始候选切片数量
     * @return 按重排相关性排序后的合法候选切片下标
     */
    private List<Integer> readRerankedIndexes(JsonNode response, int documentCount) {
        Set<Integer> indexes = new LinkedHashSet<>();
        response.path("results").forEach(result -> {
            int index = result.path("index").asInt(-1);
            if (index >= 0 && index < documentCount) {
                indexes.add(index);
            }
        });
        if (indexes.isEmpty()) {
            throw new IllegalStateException("硅基流动重排服务未返回有效结果");
        }
        return new ArrayList<>(indexes);
    }

    /**
     * 校验模型服务 HTTP 响应是否成功。
     *
     * @param response 模型服务 HTTP 响应
     */
    private void validateResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        throw new IllegalStateException("模型服务请求失败（HTTP " + response.statusCode() + "）：" + abbreviate(response.body()));
    }

    /**
     * 校验 DeepSeek 对话能力已经配置完成。
     */
    private void validateChatEnabled() {
        if (!chatEnabled()) {
            throw new IllegalStateException("请配置并启用 KB_CHAT_ENABLED、KB_CHAT_BASE_URL、KB_CHAT_API_KEY 和 KB_CHAT_MODEL");
        }
    }

    /**
     * 校验硅基流动向量能力已经配置完成。
     */
    private void validateEmbeddingEnabled() {
        if (!embeddingEnabled()) {
            throw new IllegalStateException("请配置并启用 KB_EMBEDDING_ENABLED、KB_EMBEDDING_BASE_URL、KB_EMBEDDING_API_KEY 和 KB_EMBEDDING_MODEL");
        }
    }

    /**
     * 校验硅基流动重排能力已经配置完成。
     */
    private void validateRerankEnabled() {
        if (!rerankEnabled()) {
            throw new IllegalStateException("请配置并启用 KB_RERANK_ENABLED 和 KB_RERANK_MODEL");
        }
    }

    /**
     * 校验并返回去除首尾空白后的配置值。
     *
     * @param value 配置原始值
     * @param propertyName 配置项名称
     * @return 有效的配置值
     */
    private String requireValue(String value, String propertyName) {
        if (!hasText(value)) {
            throw new IllegalStateException("请配置 " + propertyName);
        }
        return value.trim();
    }

    /**
     * 判断字符串是否包含非空白字符。
     *
     * @param value 待判断的字符串
     * @return 包含非空白字符时返回 true
     */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 移除服务地址末尾多余的斜杠。
     *
     * @param value 原始服务地址
     * @return 标准化后的服务地址
     */
    private String trimTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    /**
     * 截断过长的上游错误响应，避免异常信息无限膨胀。
     *
     * @param value 原始错误内容
     * @return 最多五百个字符的错误内容
     */
    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(value.length(), 500));
    }
}

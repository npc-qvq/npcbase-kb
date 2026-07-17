package cloud.npcbase.kb.ai;

import cloud.npcbase.kb.config.KbProperties;
import cloud.npcbase.kb.ingest.DocumentChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 维护 Qdrant 中的文档切片向量，并提供语义检索能力。
 *
 * @author NPC
 * @date 2026-07-15 18:27:07
 */
@Service
@Slf4j
public class QdrantVectorService {

    /**
     * 知识库服务配置。
     */
    private final KbProperties properties;

    /**
     * JSON 序列化和反序列化工具。
     */
    private final ObjectMapper objectMapper;

    /**
     * 调用 Qdrant REST 接口的 HTTP 客户端。
     */
    private final HttpClient httpClient;

    /**
     * 当前 collection 已确认的向量维度。
     */
    private volatile Integer collectionDimension;

    /**
     * 创建 Qdrant 向量服务。
     *
     * @param properties 知识库服务配置
     * @param objectMapper JSON 序列化和反序列化工具
     */
    public QdrantVectorService(KbProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * 判断当前是否启用向量索引功能。
     *
     * @return 已启用时返回 true
     */
    public boolean enabled() {
        String embeddingModel = properties.getEmbedding().getModel();
        return properties.getEmbedding().isEnabled() && embeddingModel != null && !embeddingModel.isBlank();
    }

    /**
     * 删除旧向量后，写入指定文档的完整切片向量。
     *
     * @param documentId 文档主键
     * @param chunks 文档文本切片
     * @param vectors 与文本切片一一对应的向量
     */
    public void replaceDocument(String documentId, List<DocumentChunk> chunks, List<List<Float>> vectors) {
        validateChunksAndVectors(chunks, vectors);
        if (chunks.isEmpty()) {
            return;
        }
        // 确保 Qdrant collection 已创建且向量维度正确。
        ensureCollection(vectors.get(0).size());
        // 删除该文档历史版本的向量数据。
        deleteDocument(documentId);
        List<Map<String, Object>> points = buildPoints(chunks, vectors);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("points", points);
        // 向 Qdrant 批量写入文档切片向量。
        request("PUT", "/collections/" + getCollectionName() + "/points?wait=true", requestBody);
    }

    /**
     * 删除指定文档在 Qdrant 中的所有向量。
     *
     * @param documentId 文档主键
     */
    public void deleteDocument(String documentId) {
        if (!enabled()) {
            return;
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("filter", buildDocumentFilter(documentId));

        try {
            // 根据文档主键筛选并删除 Qdrant 中的向量。
            request("POST", "/collections/" + getCollectionName() + "/points/delete?wait=true", requestBody);
        } catch (CollectionNotFoundException exception) {
            // 尚未创建 collection 时不存在可删除向量，资料删除仍应继续清理数据库和文件。
            log.error("Qdrant collection不存在，跳过删除向量，documentId={}", documentId);
        } catch (Exception exception) {
            // Qdrant删除失败不能影响业务文档删除流程。
            log.error("删除Qdrant向量失败，documentId={}", documentId, exception);
        }
    }

    /**
     * 根据查询向量检索最相近的文档切片。
     *
     * @param vector 查询向量
     * @param limit 最大返回数量
     * @return 相似度降序排列的切片匹配结果
     */
    public List<Match> search(List<Float> vector, int limit) {
        if (vector == null || vector.isEmpty()) {
            throw new IllegalArgumentException("查询向量不能为空");
        }
        // 确保 Qdrant collection 已创建且向量维度正确。
        ensureCollection(vector.size());
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", vector);
        requestBody.put("limit", limit);
        requestBody.put("with_payload", true);
        // 调用 Qdrant 1.18 推荐的统一查询接口检索相似文档切片。
        JsonNode response = request("POST", "/collections/" + getCollectionName() + "/points/query", requestBody);
        return readMatches(response);
    }

    /**
     * 校验 collection 是否存在，以及维度是否与当前模型一致。
     *
     * @param dimension 当前模型生成的向量维度
     */
    private synchronized void ensureCollection(int dimension) {
        if (collectionDimension != null) {
            validateCollectionDimension(dimension);
            return;
        }
        String collectionPath = "/collections/" + getCollectionName();
        try {
            // 查询现有 collection 的向量维度。
            JsonNode response = request("GET", collectionPath, null);
            int existingDimension = response.path("result").path("config").path("params").path("vectors").path("size").asInt();
            validateExistingDimension(existingDimension, dimension);
        } catch (CollectionNotFoundException exception) {
            // 创建与当前模型向量维度匹配的 collection。
            createCollection(collectionPath, dimension);
        }
        collectionDimension = dimension;
    }

    /**
     * 校验缓存的 collection 维度。
     *
     * @param dimension 当前模型向量维度
     */
    private void validateCollectionDimension(int dimension) {
        if (collectionDimension.intValue() != dimension) {
            throw new IllegalStateException("当前知识库向量维度与所配置 embedding 模型不一致；请使用独立 collection 或重新索引文档");
        }
    }

    /**
     * 校验服务器 collection 维度。
     *
     * @param existingDimension 服务器已有 collection 维度
     * @param expectedDimension 当前模型向量维度
     */
    private void validateExistingDimension(int existingDimension, int expectedDimension) {
        if (existingDimension != expectedDimension) {
            throw new IllegalStateException("Qdrant collection 向量维度为 " + existingDimension + "，但模型返回 " + expectedDimension);
        }
    }

    /**
     * 创建 Qdrant collection。
     *
     * @param collectionPath collection 接口路径
     * @param dimension 向量维度
     */
    private void createCollection(String collectionPath, int dimension) {
        Map<String, Object> vectorConfig = new HashMap<>();
        vectorConfig.put("size", dimension);
        vectorConfig.put("distance", "Cosine");
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("vectors", vectorConfig);
        // 创建用于存储知识库切片向量的 collection。
        request("PUT", collectionPath, requestBody);
    }

    /**
     * 构造 Qdrant 批量写入 point 列表。
     *
     * @param chunks 文档切片列表
     * @param vectors 文档向量列表
     * @return Qdrant point 请求对象列表
     */
    private List<Map<String, Object>> buildPoints(List<DocumentChunk> chunks, List<List<Float>> vectors) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            chunk.setQdrantPointId(chunk.getId());
            Map<String, Object> payload = new HashMap<>();
            payload.put("chunkId", chunk.getId());
            payload.put("documentId", chunk.getDocumentId());
            payload.put("chunkNo", chunk.getChunkNo());
            payload.put("text", chunk.getContent());
            Map<String, Object> point = new HashMap<>();
            point.put("id", chunk.getId());
            point.put("vector", vectors.get(index));
            point.put("payload", payload);
            points.add(point);
        }
        return points;
    }

    /**
     * 构造按文档主键过滤 Qdrant point 的条件。
     *
     * @param documentId 文档主键
     * @return Qdrant 过滤条件
     */
    private Map<String, Object> buildDocumentFilter(String documentId) {
        Map<String, Object> match = new HashMap<>();
        match.put("value", documentId);
        Map<String, Object> condition = new HashMap<>();
        condition.put("key", "documentId");
        condition.put("match", match);
        List<Map<String, Object>> conditions = new ArrayList<>();
        conditions.add(condition);
        Map<String, Object> filter = new HashMap<>();
        filter.put("must", conditions);
        return filter;
    }

    /**
     * 将 Qdrant 检索响应转换为业务匹配结果。
     *
     * @param response Qdrant 检索响应
     * @return 业务匹配结果列表
     */
    private List<Match> readMatches(JsonNode response) {
        List<Match> matches = new ArrayList<>();
        for (JsonNode result : response.path("result").path("points")) {
            JsonNode payload = result.path("payload");
            matches.add(new Match(payload.path("chunkId").asText(), payload.path("documentId").asText(),
                    payload.path("chunkNo").asInt(), payload.path("text").asText(), result.path("score").asDouble()));
        }
        return matches;
    }

    /**
     * 向 Qdrant 发送 REST 请求。
     *
     * @param method HTTP 请求方法
     * @param path Qdrant 接口路径
     * @param requestBody 请求体，GET 请求可为 null
     * @return Qdrant JSON 响应
     */
    private JsonNode request(String method, String path, Object requestBody) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(buildUrl(path)))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            String apiKey = properties.getQdrant().getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("api-key", apiKey.trim());
            }
            if ("GET".equals(method)) {
                requestBuilder.GET();
            } else {
                requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)));
            }
            // 发送 Qdrant 请求并读取响应。
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            validateQdrantResponse(response);
            // 将 Qdrant 响应转换为 JSON 节点对象。
            return objectMapper.readTree(response.body());
        } catch (CollectionNotFoundException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("调用 Qdrant 失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 校验 Qdrant HTTP 响应状态。
     *
     * @param response Qdrant HTTP 响应
     */
    private void validateQdrantResponse(HttpResponse<String> response) {
        if (response.statusCode() == 404) {
            throw new CollectionNotFoundException();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Qdrant 请求失败（HTTP " + response.statusCode() + "）：" + abbreviate(response.body()));
        }
    }

    /**
     * 组装 Qdrant 服务请求地址。
     *
     * @param path Qdrant 接口路径
     * @return Qdrant 完整请求地址
     */
    private String buildUrl(String path) {
        String configuredUrl = properties.getQdrant().getUrl();
        if (configuredUrl != null && !configuredUrl.isBlank()) {
            return configuredUrl.replaceAll("/+$", "") + path;
        }
        return "http://" + properties.getQdrant().getHost() + ":" + properties.getQdrant().getPort() + path;
    }

    /**
     * 获取并校验 Qdrant collection 名称。
     *
     * @return 合法的 collection 名称
     */
    private String getCollectionName() {
        String collection = properties.getQdrant().getCollection();
        if (collection == null || !collection.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalStateException("KB_QDRANT_COLLECTION 配置不合法");
        }
        return collection;
    }

    /**
     * 校验切片和向量列表是否可用于写入。
     *
     * @param chunks 文档切片列表
     * @param vectors 文档向量列表
     */
    private void validateChunksAndVectors(List<DocumentChunk> chunks, List<List<Float>> vectors) {
        if (chunks == null || vectors == null || chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("切片数量与向量数量不一致");
        }
    }

    /**
     * 截断过长的错误信息。
     *
     * @param value 原始错误信息
     * @return 最多五百字符的错误信息
     */
    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(value.length(), 500));
    }

    /**
     * 标识 Qdrant collection 不存在的内部异常。
     *
     * @author NPC
     * @date 2026-07-15 18:27:07
     */
    private static class CollectionNotFoundException extends RuntimeException {
    }

    /**
     * 表示一次 Qdrant 语义检索命中的文档切片。
     *
     * @author NPC
     * @date 2026-07-15 18:27:07
     */
    public static class Match {

        /**
         * 文档切片主键。
         */
        private final String chunkId;

        /**
         * 所属文档主键。
         */
        private final String documentId;

        /**
         * 文档内切片序号。
         */
        private final int chunkNo;

        /**
         * 切片文本内容。
         */
        private final String text;

        /**
         * 向量相似度得分。
         */
        private final double score;

        /**
         * 创建 Qdrant 检索匹配结果。
         *
         * @param chunkId 文档切片主键
         * @param documentId 所属文档主键
         * @param chunkNo 文档内切片序号
         * @param text 切片文本内容
         * @param score 向量相似度得分
         */
        public Match(String chunkId, String documentId, int chunkNo, String text, double score) {
            this.chunkId = chunkId;
            this.documentId = documentId;
            this.chunkNo = chunkNo;
            this.text = text;
            this.score = score;
        }

        /**
         * 获取文档切片主键。
         *
         * @return 文档切片主键
         */
        public String getChunkId() {
            return chunkId;
        }

        /**
         * 获取所属文档主键。
         *
         * @return 所属文档主键
         */
        public String getDocumentId() {
            return documentId;
        }

        /**
         * 获取文档内切片序号。
         *
         * @return 文档内切片序号
         */
        public int getChunkNo() {
            return chunkNo;
        }

        /**
         * 获取切片文本内容。
         *
         * @return 切片文本内容
         */
        public String getText() {
            return text;
        }

        /**
         * 获取向量相似度得分。
         *
         * @return 向量相似度得分
         */
        public double getScore() {
            return score;
        }
    }
}

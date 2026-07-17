package cloud.npcbase.kb.ingest;

import cloud.npcbase.kb.ai.OpenAiCompatibleClient;
import cloud.npcbase.kb.ai.QdrantVectorService;
import cloud.npcbase.kb.document.KbDocument;
import cloud.npcbase.kb.document.KbDocumentRepository;
import cloud.npcbase.kb.storage.StorageService;
import org.apache.tika.Tika;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 轮询执行文档解析、切片和向量索引任务。
 *
 * @author NPC
 * @data 2026-07-15
 */
@Component
public class IngestionWorker {

    /**
     * 单个切片的最大字符数。
     */
    private static final int CHUNK_SIZE = 1800;

    /**
     * 相邻切片的重叠字符数。
     */
    private static final int CHUNK_OVERLAP = 250;

    /**
     * 文档解析任务数据访问对象。
     */
    private final IngestTaskRepository ingestTaskRepository;

    /**
     * 文档数据访问对象。
     */
    private final KbDocumentRepository documentRepository;

    /**
     * 文档切片数据访问对象。
     */
    private final DocumentChunkRepository chunkRepository;

    /**
     * 文档文件存储服务。
     */
    private final StorageService storageService;

    /**
     * OpenAI 兼容模型客户端。
     */
    private final OpenAiCompatibleClient aiClient;

    /**
     * Qdrant 向量操作服务。
     */
    private final QdrantVectorService vectorService;

    /**
     * Apache Tika 文档文本提取器。
     */
    private final Tika tika;

    /**
     * 创建文档解析任务执行器。
     *
     * @param ingestTaskRepository 文档解析任务数据访问对象
     * @param documentRepository 文档数据访问对象
     * @param chunkRepository 文档切片数据访问对象
     * @param storageService 文档文件存储服务
     * @param aiClient OpenAI 兼容模型客户端
     * @param vectorService Qdrant 向量操作服务
     */
    public IngestionWorker(IngestTaskRepository ingestTaskRepository,
                           KbDocumentRepository documentRepository,
                           DocumentChunkRepository chunkRepository,
                           StorageService storageService,
                           OpenAiCompatibleClient aiClient,
                           QdrantVectorService vectorService) {
        this.ingestTaskRepository = ingestTaskRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.storageService = storageService;
        this.aiClient = aiClient;
        this.vectorService = vectorService;
        this.tika = new Tika();
    }

    /**
     * 定时查询并执行待处理的文档解析任务。
     */
    @Scheduled(fixedDelayString = "${kb.ingest-delay-ms:5000}")
    public void processPendingTasks() {
        // 查询最早创建的待处理任务并逐条执行。
        ingestTaskRepository.findTop5ByStatusOrderByCreatedAtAsc("PENDING").forEach(this::process);
    }

    /**
     * 解析一份文档、保存切片，并在启用 AI 时写入向量库。
     *
     * @param task 待执行的文档解析任务
     */
    @Transactional
    protected void process(IngestTask task) {
        // 根据任务中的文档主键查询待处理文档。
        KbDocument document = documentRepository.selectById(task.getDocumentId());
        if (document == null) {
            task.fail("文档已删除");
            ingestTaskRepository.updateById(task);
            return;
        }
        try {
            task.start();
            document.markParsing();
            documentRepository.updateById(document);
            // 从上传文件中提取纯文本内容。
            String text = parse(document);
            if (text.trim().isEmpty()) {
                throw new IllegalArgumentException("未能从文件中提取有效文本");
            }
            // 保存解析文本，用于后续追溯和重建索引。
            String parsedPath = storageService.saveParsed(document.getId(), text);
            // 删除旧切片，避免重新索引时产生重复内容。
            chunkRepository.deleteByDocumentId(document.getId());
            List<DocumentChunk> chunks = split(document.getId(), text);
            // 持久化新的文本切片，支持关键词检索。
            chunks.forEach(chunkRepository::insert);
            indexVectorsIfEnabled(document, chunks);
            document.markIndexed(parsedPath);
            task.done();
            documentRepository.updateById(document);
            ingestTaskRepository.updateById(task);
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            document.markFailed(message);
            task.fail(message);
            documentRepository.updateById(document);
            ingestTaskRepository.updateById(task);
        }
    }

    /**
     * 在启用 AI 后为文本切片生成向量并写入 Qdrant。
     *
     * @param document 当前处理的文档
     * @param chunks 已保存的文档切片
     */
    private void indexVectorsIfEnabled(KbDocument document, List<DocumentChunk> chunks) {
        if (!vectorService.enabled()) {
            return;
        }
        document.markIndexing();
        documentRepository.updateById(document);
        List<String> texts = chunks.stream().map(DocumentChunk::getContent).collect(Collectors.toList());
        // 调用模型服务生成文档切片向量。
        List<List<Float>> vectors = aiClient.embed(texts);
        // 将文档全部切片及其向量写入 Qdrant。
        vectorService.replaceDocument(document.getId(), chunks, vectors);
        // 更新切片关联的 Qdrant point 标识。
        chunks.forEach(chunkRepository::updateById);
    }

    /**
     * 从原始文件中提取纯文本。
     *
     * @param document 需要解析的文档
     * @return 提取到的纯文本
     * @throws Exception 文件读取或 Tika 解析失败时抛出
     */
    private String parse(KbDocument document) throws Exception {
        Path path = Path.of(document.getStoragePath());
        String fileType = document.getFileType();
        if ("md".equals(fileType) || "markdown".equals(fileType) || "txt".equals(fileType)) {
            // 读取文本类文件的原始内容。
            return Files.readString(path);
        }
        // 使用 Tika 解析 PDF、DOCX 等二进制文档。
        return tika.parseToString(path);
    }

    /**
     * 将文本按照长度和重叠区间拆分成多个切片。
     *
     * @param documentId 文档主键
     * @param text 完整解析文本
     * @return 有序的文本切片列表
     */
    private List<DocumentChunk> split(String documentId, String text) {
        String normalizedText = text.replace("\r", "").replaceAll("[ \\t]+", " ").trim();
        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        int chunkNo = 1;
        while (start < normalizedText.length()) {
            int end = Math.min(start + CHUNK_SIZE, normalizedText.length());
            int newline = normalizedText.lastIndexOf("\n", end);
            if (newline > start + CHUNK_SIZE / 2) {
                end = newline;
            }
            chunks.add(new DocumentChunk(documentId, chunkNo, normalizedText.substring(start, end).trim()));
            chunkNo++;
            if (end == normalizedText.length()) {
                break;
            }
            start = Math.max(end - CHUNK_OVERLAP, start + 1);
        }
        return chunks;
    }
}

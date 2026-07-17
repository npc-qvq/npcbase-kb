package cloud.npcbase.kb.document;

import cloud.npcbase.kb.ai.QdrantVectorService;
import cloud.npcbase.kb.ingest.DocumentChunkRepository;
import cloud.npcbase.kb.ingest.IngestTask;
import cloud.npcbase.kb.ingest.IngestTaskRepository;
import cloud.npcbase.kb.storage.StorageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

/**
 * 处理知识库文档的上传、查询、删除和重新索引业务。
 *
 * @author NPC
 * @date 2026-07-15 18:27:07
 */
@Service
public class DocumentService {

    /**
     * 允许上传的文件扩展名。
     */
    private static final Set<String> ALLOWED_FILE_TYPES = Set.of("md", "markdown", "txt", "pdf", "docx");

    /**
     * 文档数据访问对象。
     */
    private final KbDocumentRepository documentRepository;

    /**
     * 文档解析任务数据访问对象。
     */
    private final IngestTaskRepository ingestTaskRepository;

    /**
     * 文档文本切片数据访问对象，用于在删除文档时主动清理无外键约束的切片。
     */
    private final DocumentChunkRepository chunkRepository;

    /**
     * 文件本地存储服务。
     */
    private final StorageService storageService;

    /**
     * Qdrant 向量数据维护服务。
     */
    private final QdrantVectorService vectorService;

    /**
     * 创建文档业务服务。
     *
     * @param documentRepository 文档数据访问对象
     * @param ingestTaskRepository 文档解析任务数据访问对象
     * @param chunkRepository 文档文本切片数据访问对象
     * @param storageService 文件本地存储服务
     * @param vectorService Qdrant 向量数据维护服务
     */
    public DocumentService(KbDocumentRepository documentRepository,
                           IngestTaskRepository ingestTaskRepository,
                           DocumentChunkRepository chunkRepository,
                           StorageService storageService,
                           QdrantVectorService vectorService) {
        this.documentRepository = documentRepository;
        this.ingestTaskRepository = ingestTaskRepository;
        this.chunkRepository = chunkRepository;
        this.storageService = storageService;
        this.vectorService = vectorService;
    }

    /**
     * 保存上传文件并创建待解析任务。
     *
     * @param file 上传的原始文件
     * @param title 用户指定的文档标题
     * @return 新建的知识库文档
     * @throws IOException 保存原始文件失败时抛出
     */
    @Transactional
    public KbDocument upload(MultipartFile file, String title) throws IOException {
        validateFile(file);
        String originalFilename = getOriginalFilename(file);
        String fileType = getExtension(originalFilename);
        validateFileType(fileType);
        // 保存上传的原始文件并取得其存储路径。
        String storagePath = storageService.saveOriginal(file);
        KbDocument document = new KbDocument(resolveTitle(title, originalFilename), originalFilename, fileType, file.getSize(), storagePath);
        // 持久化文档元数据，供后续解析任务读取。
        documentRepository.insert(document);
        // 创建异步解析和索引任务。
        ingestTaskRepository.insert(new IngestTask(document.getId()));
        return document;
    }

    /**
     * 分页查询文档列表。
     *
     * @param page 页码，从零开始
     * @param size 每页条数
     * @return 按创建时间倒序的文档分页结果
     */
    public Page<KbDocument> list(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        // 按创建时间倒序查询文档列表。
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<KbDocument> result = documentRepository.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(safePage + 1, safeSize),
                new LambdaQueryWrapper<KbDocument>().orderByDesc(KbDocument::getCreatedAt));
        return new PageImpl<>(result.getRecords(), PageRequest.of(safePage, safeSize), result.getTotal());
    }

    /**
     * 根据主键查询文档。
     *
     * @param documentId 文档主键
     * @return 对应的知识库文档
     */
    public KbDocument get(String documentId) {
        // 根据主键查询文档，不存在时返回空结果。
        KbDocument document = documentRepository.selectById(documentId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在");
        }
        return document;
    }

    /**
     * 删除文档、文件和关联向量。
     *
     * @param documentId 文档主键
     * @throws IOException 删除本地文件失败时抛出
     */
    @Transactional
    public void delete(String documentId) throws IOException {
        // 查询待删除文档，确保其存在。
        KbDocument document = get(documentId);
        // 删除文档对应的 Qdrant 向量数据。
        vectorService.deleteDocument(documentId);
        // 删除上传的原始文件。
        storageService.delete(document.getStoragePath());
        // 删除解析后的文本文件。
        storageService.delete(document.getParsedPath());
        // 删除文档及数据库级联的切片和任务记录。
        // 未使用数据库外键，主动清理文本切片，避免关键词检索命中已经删除的资料。
        chunkRepository.deleteByDocumentId(document.getId());
        // 未使用数据库外键，主动清理待执行任务，避免后台继续处理已删除资料。
        ingestTaskRepository.deleteByDocumentId(document.getId());
        // 在关联数据均清理完成后删除文档元数据，避免留下孤儿记录。
        documentRepository.deleteById(document.getId());
    }

    /**
     * 为指定文档新增一次解析和索引任务。
     *
     * @param documentId 文档主键
     */
    @Transactional
    public void reindex(String documentId) {
        // 校验需要重新索引的文档存在。
        get(documentId);
        // 创建新的异步解析和索引任务。
        ingestTaskRepository.insert(new IngestTask(documentId));
    }

    /**
     * 校验上传文件是否为空。
     *
     * @param file 上传文件
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择非空文件");
        }
    }

    /**
     * 校验文件扩展名是否受支持。
     *
     * @param fileType 文件扩展名
     */
    private void validateFileType(String fileType) {
        if (!ALLOWED_FILE_TYPES.contains(fileType)) {
            throw new IllegalArgumentException("仅支持 Markdown、TXT、PDF、DOCX");
        }
    }

    /**
     * 获取上传文件的原始名称。
     *
     * @param file 上传文件
     * @return 原始文件名称
     */
    private String getOriginalFilename(MultipartFile file) {
        return file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
    }

    /**
     * 根据用户输入或文件名确定文档标题。
     *
     * @param title 用户输入标题
     * @param originalFilename 原始文件名称
     * @return 有效文档标题
     */
    private String resolveTitle(String title, String originalFilename) {
        if (title == null || title.trim().isEmpty()) {
            return removeExtension(originalFilename);
        }
        return title.trim();
    }

    /**
     * 获取文件扩展名。
     *
     * @param filename 文件名称
     * @return 小写扩展名，无扩展名时返回空字符串
     */
    private String getExtension(String filename) {
        int index = filename.lastIndexOf('.');
        if (index < 0) {
            return "";
        }
        return filename.substring(index + 1).toLowerCase();
    }

    /**
     * 移除文件名扩展名。
     *
     * @param filename 文件名称
     * @return 不含扩展名的文件名称
     */
    private String removeExtension(String filename) {
        int index = filename.lastIndexOf('.');
        if (index < 0) {
            return filename;
        }
        return filename.substring(0, index);
    }
}

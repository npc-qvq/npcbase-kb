package cloud.npcbase.kb.document;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 提供知识库文档上传、查询、重建索引和删除接口。
 *
 * @author NPC
 * @date 2026-07-15 18:27:07
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    /**
     * 知识库文档业务服务。
     */
    private final DocumentService documentService;

    /**
     * 创建知识库文档接口控制器。
     *
     * @param documentService 知识库文档业务服务
     */
    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 上传文档并创建异步解析任务。
     *
     * @param file 用户上传的文档文件
     * @param title 用户指定的文档标题，可为空
     * @return 新建文档的接口展示对象
     * @throws IOException 当保存上传文件失败时抛出
     */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentView upload(@RequestPart("file") MultipartFile file,
                               @RequestParam(required = false) String title) throws IOException {
        // 保存上传文档并创建后续解析任务。
        KbDocument document = documentService.upload(file, title);
        return DocumentView.from(document);
    }

    /**
     * 分页查询知识库文档列表。
     *
     * @param page 页码，从零开始
     * @param size 每页条数
     * @return 文档展示对象分页结果
     */
    @GetMapping
    public Page<DocumentView> list(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        // 查询文档分页结果并转换为接口展示对象。
        return documentService.list(page, size).map(DocumentView::from);
    }

    /**
     * 查询指定文档详情。
     *
     * @param id 文档主键
     * @return 文档展示对象
     */
    @GetMapping("/{id}")
    public DocumentView get(@PathVariable String id) {
        // 查询指定文档的元数据。
        KbDocument document = documentService.get(id);
        return DocumentView.from(document);
    }

    /**
     * 为指定文档创建重新解析和索引任务。
     *
     * @param id 文档主键
     * @return 任务创建结果消息
     */
    @PostMapping("/{id}/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> reindex(@PathVariable String id) {
        // 创建该文档新的异步解析和索引任务。
        documentService.reindex(id);
        Map<String, String> result = new HashMap<>();
        result.put("message", "已加入重新索引队列");
        return result;
    }

    /**
     * 删除指定文档及其关联文件和索引。
     *
     * @param id 文档主键
     * @throws IOException 当删除本地文件失败时抛出
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) throws IOException {
        // 删除文档及其关联的本地文件和向量索引。
        documentService.delete(id);
    }

    /**
     * 表示文档接口返回的展示数据。
     *
     * @param id 文档主键
     * @param title 文档标题
     * @param originalFilename 用户上传的原始文件名
     * @param fileType 文档文件类型
     * @param fileSize 文档文件大小，单位为字节
     * @param status 文档处理状态
     * @param failureReason 文档处理失败原因
     * @author NPC
     * @date 2026-07-15 18:27:07
     */
    public record DocumentView(String id, String title, String originalFilename, String fileType,
                               long fileSize, DocumentStatus status, String failureReason) {

        /**
         * 将文档实体转换为文档接口展示数据。
         *
         * @param document 知识库文档实体
         * @return 文档接口展示数据
         */
        private static DocumentView from(KbDocument document) {
            return new DocumentView(document.getId(), document.getTitle(), document.getOriginalFilename(),
                    document.getFileType(), document.getFileSize(), document.getStatus(), document.getFailureReason());
        }
    }
}

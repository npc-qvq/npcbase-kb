package cloud.npcbase.kb.storage;

import cloud.npcbase.kb.config.KbProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 保存和删除知识库原文件及解析文本文件。
 *
 * @author NPC
 * @date 2026-07-15 18:27:07
 */
@Service
public class StorageService {

    /**
     * 文档文件的规范化存储根目录。
     */
    private final Path root;

    /**
     * 创建本地文件存储服务。
     *
     * @param properties 知识库服务配置
     */
    public StorageService(KbProperties properties) {
        this.root = Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
    }

    /**
     * 保存用户上传的原始文件。
     *
     * @param file 用户上传的文件
     * @return 原始文件在本地存储中的绝对路径
     * @throws IOException 当创建目录或写入文件失败时抛出
     */
    public String saveOriginal(MultipartFile file) throws IOException {
        String filename = createSafeFilename(file.getOriginalFilename());
        Path target = root.resolve("files").resolve(filename).normalize();
        validateStoragePath(target);
        // 创建原始文件所在目录。
        Files.createDirectories(target.getParent());
        // 将上传文件流复制到知识库原始文件目录。
        try (var inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target.toString();
    }

    /**
     * 保存文档解析得到的纯文本。
     *
     * @param documentId 文档主键
     * @param text 文档解析后的纯文本
     * @return 解析文本在本地存储中的绝对路径
     * @throws IOException 当创建目录或写入文件失败时抛出
     */
    public String saveParsed(String documentId, String text) throws IOException {
        Path target = root.resolve("parsed").resolve(documentId + ".txt").normalize();
        validateStoragePath(target);
        // 创建解析文本所在目录。
        Files.createDirectories(target.getParent());
        // 覆盖写入该文档最近一次解析得到的纯文本。
        Files.writeString(target, text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return target.toString();
    }

    /**
     * 删除指定路径的本地文件。
     *
     * @param path 待删除文件的绝对路径，可为 null
     * @throws IOException 当删除文件失败时抛出
     */
    public void delete(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        Path target = Path.of(path).toAbsolutePath().normalize();
        validateStoragePath(target);
        // 删除文档原文件或解析文本文件，不存在时直接忽略。
        Files.deleteIfExists(target);
    }

    /**
     * 为上传文件创建不可预测的安全文件名。
     *
     * @param originalFilename 用户上传的原始文件名
     * @return 带原扩展名的随机文件名
     */
    private String createSafeFilename(String originalFilename) {
        return UUID.randomUUID() + getExtension(originalFilename);
    }

    /**
     * 获取文件名的扩展名。
     *
     * @param filename 文件名
     * @return 以点号开头的小写扩展名，无扩展名时返回空字符串
     */
    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.')).toLowerCase();
    }

    /**
     * 校验文件路径仍在知识库存储根目录内。
     *
     * @param target 待校验的目标路径
     * @throws IllegalArgumentException 当目标路径越出存储根目录时抛出
     */
    private void validateStoragePath(Path target) {
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法文件路径");
        }
    }
}

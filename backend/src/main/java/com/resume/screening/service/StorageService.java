package com.resume.screening.service;

import com.resume.screening.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地文件存储。路径全部由服务端生成（yyyy/MM/dd/uuid.pdf），
 * 绝不拼接用户上传的文件名（需求 §10 安全）。
 */
@Slf4j
@Service
public class StorageService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final Path root;

    public StorageService(AppProperties props) {
        this.root = Paths.get(props.getUploadDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            log.info("文件存储根目录：{}", root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建上传目录：" + root, e);
        }
    }

    /** @return 相对 uploads 根的路径，如 2026/10/26/3f2a9c1e.pdf */
    public String save(byte[] bytes, String originalName) {
        String sub = LocalDate.now().format(DAY);
        String ext = extensionOf(originalName);
        String relative = sub + "/" + UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return relative;
        } catch (IOException e) {
            throw new IllegalStateException("写入文件失败：" + relative, e);
        }
    }

    public byte[] read(String relativePath) {
        try {
            return Files.readAllBytes(resolve(relativePath));
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败：" + relativePath, e);
        }
    }

    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    /** 解析相对路径并做越界校验，防止 ../ 逃逸 */
    public Path resolve(String relativePath) {
        Path p = root.resolve(relativePath).normalize();
        if (!p.startsWith(root)) {
            throw new SecurityException("非法文件路径：" + relativePath);
        }
        return p;
    }

    private String extensionOf(String name) {
        if (name == null) {
            return ".pdf";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return ".pdf";
        }
        String ext = name.substring(dot).toLowerCase();
        return ext.matches("\\.(pdf)") ? ext : ".pdf";
    }
}

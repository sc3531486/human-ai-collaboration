package com.example.bank.business.evidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地文件存储实现。根目录可配置，默认 {@code records/evidence}。
 *
 * <p>所有路径都必须落在根目录内，防止 {@code ..} 穿越；写入先落临时文件再移动，
 * 避免半截文件被当成完整证据。</p>
 */
@Component
public class LocalStorageAdapter implements StoragePort {

    private final Path root;

    public LocalStorageAdapter(@Value("${evidence.storage.root:records/evidence}") String root) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(String relativePath, byte[] data) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IllegalArgumentException("存储路径不能为空");
        }
        byte[] bytes = data == null ? new byte[0] : data;
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "evidence-", ".tmp");
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredFile(relativePath, bytes.length);
        } catch (IOException error) {
            throw new IllegalStateException("证据文件写入失败：" + relativePath, error);
        }
    }

    @Override
    public byte[] read(String relativePath) {
        Path target = resolve(relativePath);
        try {
            return Files.readAllBytes(target);
        } catch (IOException error) {
            throw new IllegalStateException("证据文件读取失败：" + relativePath, error);
        }
    }

    /** 解析并校验路径必须位于根目录内。 */
    private Path resolve(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("证据路径越界：" + relativePath);
        }
        return target;
    }
}

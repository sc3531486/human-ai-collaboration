package com.example.bank.business.evidence;

/**
 * 文件存储端口：业务只关心"相对路径 + 字节"，本地文件、对象存储或厂商存储各自实现。
 *
 * <p>相对路径由调用方按业务规则生成，存储实现不得把绝对路径暴露给业务层。</p>
 */
public interface StoragePort {

    /** 写入文件，返回实际相对路径与大小。 */
    StoredFile store(String relativePath, byte[] data);

    /** 读取文件；不存在时抛异常。 */
    byte[] read(String relativePath);

    class StoredFile {
        public final String relativePath;
        public final long size;

        public StoredFile(String relativePath, long size) {
            this.relativePath = relativePath;
            this.size = size;
        }
    }
}

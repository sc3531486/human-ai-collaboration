package com.example.bank.business.evidence;

/**
 * 证据归档端口：图片归档、视频归档、单据归档可以是不同系统，按目标系统路由。
 *
 * <p>业务层只负责决定"归到哪个目标"，协议由适配器实现；目标系统与地址从
 * {@code sys_config} 读取（{@code evidence.archive.<mediaType>.target/url}）。</p>
 */
public interface EvidenceArchivePort {

    ArchiveReceipt archive(ArchiveRequest request);

    class ArchiveRequest {
        public String evidenceId;
        public String bizNo;
        public String mediaType;
        public String mediaSubtype;
        public String filePath;
        public String fileHash;
        public String targetSystem;
        public String url;
    }

    class ArchiveReceipt {
        public String targetSystem;
        public String archiveRef;
        public boolean success;
        public String message;
    }
}

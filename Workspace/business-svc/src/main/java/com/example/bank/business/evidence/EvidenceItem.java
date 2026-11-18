package com.example.bank.business.evidence;

import java.util.ArrayList;
import java.util.List;

/** 一条存证/文件证据：一张图、一段视频或一份文档。 */
public class EvidenceItem {

    public String evidenceId;
    public String bizNo;
    public String bizDate;
    public String mediaType;
    public String mediaSubtype;
    public int fileIndex;
    public String fileName;
    public String filePath;
    public long fileSize;
    public String fileHash;
    public String mimeType;
    public String status;
    public String archiveSystem;
    public String archiveRef;
    public String archivedAt;
    public int attempts;
    public String lastError;
    public String capturedAt;
    public String createdAt;
    public String updatedAt;
    public List<EvidenceSegment> segments = new ArrayList<EvidenceSegment>();
}

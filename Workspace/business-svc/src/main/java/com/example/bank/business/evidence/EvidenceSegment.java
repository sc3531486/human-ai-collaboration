package com.example.bank.business.evidence;

/** 视频证据的一个分片。 */
public class EvidenceSegment {

    public long segmentId;
    public String evidenceId;
    public int segmentIndex;
    public String filePath;
    public long fileSize;
    public String fileHash;
    public long durationMs;
    public String status;
    public String startedAt;
    public String endedAt;
    public String createdAt;
}

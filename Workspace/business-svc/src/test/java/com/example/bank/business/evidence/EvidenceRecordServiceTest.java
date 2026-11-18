package com.example.bank.business.evidence;

import com.example.bank.business.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceRecordServiceTest {

    @TempDir
    Path directory;

    private EvidenceRecordService service;
    private SysConfigService config;
    private EvidenceArchivePort.ArchiveRequest captured;
    private boolean archiveSuccess = true;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:evidence-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        config = new SysConfigService(jdbc);
        config.initialize();
        EvidenceArchivePort archive = request -> {
            captured = request;
            EvidenceArchivePort.ArchiveReceipt receipt = new EvidenceArchivePort.ArchiveReceipt();
            receipt.targetSystem = request.targetSystem;
            receipt.success = archiveSuccess;
            receipt.archiveRef = archiveSuccess ? "ARCHIVE-1" : null;
            receipt.message = archiveSuccess ? null : "boom";
            return receipt;
        };
        service = new EvidenceRecordService(jdbc, new LocalStorageAdapter(directory.toString()), archive, config);
        service.initialize();
    }

    @Test
    void recordsImageWithClassifiedPathAndHash() throws Exception {
        EvidenceItem item = service.recordFile("BIZ-1", "ID_CARD_FRONT", "id-card.jpg", "image/jpeg", new byte[]{1, 2, 3});

        assertEquals("RECORDED", item.status);
        assertEquals("IMAGE", item.mediaType);
        assertEquals(1, item.fileIndex);
        assertEquals("1.jpg", item.fileName);
        assertTrue(item.filePath.matches("\\d{8}/BIZ-1/ID_CARD_FRONT/1\\.jpg"));
        assertEquals(3L, item.fileSize);
        assertEquals(64, item.fileHash.length());
        assertTrue(Files.exists(directory.resolve(item.filePath)));
        assertEquals(1, service.getFlow("BIZ-1").size());
        assertEquals(item.evidenceId, service.getItem(item.evidenceId).evidenceId);
    }

    @Test
    void appendsSealsAndDeduplicatesVideoSegments() {
        EvidenceItem first = service.appendSegment("BIZ-2", "DUAL_RECORD", null, 1, 1000, "s1.webm", new byte[]{1, 2});
        assertEquals("VIDEO", first.mediaType);
        EvidenceItem duplicate = service.appendSegment("BIZ-2", "DUAL_RECORD", first.evidenceId, 1, 1000, "s1.webm", new byte[]{1, 2});
        assertEquals(1, duplicate.segments.size());
        assertThrows(ResponseStatusException.class, () -> service.appendSegment(
                "BIZ-2", "DUAL_RECORD", first.evidenceId, 1, 1000, "s1.webm", new byte[]{9}));

        EvidenceItem second = service.appendSegment("BIZ-2", "DUAL_RECORD", first.evidenceId, 2, 2000, "s2.webm", new byte[]{3, 4, 5});
        assertEquals(2, second.segments.size());
        EvidenceItem sealed = service.seal(first.evidenceId);
        assertEquals(5L, sealed.fileSize);
        assertTrue(sealed.segments.stream().allMatch(segment -> "SEALED".equals(segment.status)));
    }

    @Test
    void archiveIsPendingWithoutConfigAndArchivedWithConfig() {
        EvidenceItem item = service.recordFile("BIZ-3", "ID_CARD_FRONT", "id.jpg", "image/jpeg", new byte[]{1});
        EvidenceItem pending = service.archive(item.evidenceId);
        assertEquals("PENDING", pending.status);
        assertNotNull(pending.lastError);

        config.set("evidence.archive.image.target", "IMAGE_ARCHIVE", "图片归档");
        config.set("evidence.archive.image.url", "http://archive.test/image", "图片归档地址");
        EvidenceItem archived = service.archive(item.evidenceId);
        assertEquals("ARCHIVED", archived.status);
        assertEquals("ARCHIVE-1", archived.archiveRef);
        assertEquals("IMAGE_ARCHIVE", captured.targetSystem);
        assertEquals("http://archive.test/image", captured.url);
    }

    @Test
    void rejectsUnknownCategoryAndEmptyFile() {
        assertThrows(ResponseStatusException.class, () -> service.recordFile(
                "BIZ-4", "UNKNOWN", "x.jpg", "image/jpeg", new byte[]{1}));
        assertThrows(ResponseStatusException.class, () -> service.recordFile(
                "BIZ-4", "ID_CARD_FRONT", "x.jpg", "image/jpeg", new byte[0]));
    }
}

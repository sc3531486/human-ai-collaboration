package com.example.bank.business.evidence;

import com.example.bank.business.service.BizRecordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaChunkServiceTest {
    @TempDir Path directory;
    private MediaChunkService media;
    private BizRecordService records;
    @BeforeEach void initialize() {
        records = mock(BizRecordService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0, 1);
        InteractionTraceService traces = new InteractionTraceService(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")), new ObjectMapper());
        traces.initialize(); media = new MediaChunkService(directory.toString(), records, jdbc, traces);
    }
    private Map<String,Object> part(int index, byte[] bytes, boolean last) throws Exception {
        return media.accept("BIZ-1", "p1", 1, "DIGITAL_HUMAN", "数字人", 10, "数字人咨询", "record-1", index, bytes, last);
    }
    @Test void networkRetryDoesNotDuplicateBytesOrRegisterTheSegmentTwice() throws Exception {
        part(0, new byte[]{1, 2, 3}, false); part(0, new byte[]{1, 2, 3}, false);
        Map<String,Object> receipt = part(1, new byte[0], true);
        part(1, new byte[0], true);
        assertThrows(ResponseStatusException.class, () -> part(2, new byte[]{9}, false));
        assertEquals(3L, receipt.get("fileSize"));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(directory.resolve(String.valueOf(receipt.get("fileUrl")).substring("/records/".length()))));
        verify(records, times(1)).addSegment(anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyInt(), anyString(), anyString());
    }
    @Test void missingChunkPreventsFalseCompletion() {
        assertThrows(ResponseStatusException.class, () -> part(1, new byte[0], true)); verifyNoInteractions(records);
    }
    @Test void persistedChunkCannotBeOverwrittenWithDifferentContent() throws Exception {
        part(0, new byte[]{1}, false);
        assertThrows(ResponseStatusException.class, () -> part(0, new byte[]{2}, false)); verifyNoInteractions(records);
    }
}

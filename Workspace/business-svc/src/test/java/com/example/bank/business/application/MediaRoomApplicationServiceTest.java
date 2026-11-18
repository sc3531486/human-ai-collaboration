package com.example.bank.business.application;
import com.example.bank.business.port.HttpMediaAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MediaRoomApplicationServiceTest {
    @Test void roomMappingSurvivesRepeatedOpenAndCloseFailureWithoutUsingWorkflow() {
        JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:rooms-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        HttpMediaAdapter media=mock(HttpMediaAdapter.class);
        MediaRoomApplicationService service=new MediaRoomApplicationService(jdbc,media);service.initialize();
        when(media.openRoom(eq("p1"),anyMap())).thenReturn(Collections.singletonMap("roomId","r1"));
        service.openRoom("p1",Collections.emptyMap());service.openRoom("p1",Collections.emptyMap());
        verify(media,times(1)).openRoom(eq("p1"),anyMap());
        doThrow(new IllegalStateException("unavailable")).doNothing().when(media).closeRoom(eq("p1"),anyMap());
        assertThrows(IllegalStateException.class,()->service.closeRoom("p1"));
        assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM biz_media_room WHERE process_id='p1'",String.class));
        service.closeRoom("p1");assertEquals("CLOSED",jdbc.queryForObject("SELECT state FROM biz_media_room WHERE process_id='p1'",String.class));
    }
}

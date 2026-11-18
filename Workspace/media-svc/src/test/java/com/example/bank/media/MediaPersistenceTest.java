package com.example.bank.media;

import com.example.bank.media.persistence.MediaEventRepository;
import com.example.bank.media.persistence.MediaIdentityResolver;
import com.example.bank.media.persistence.MediaParticipantRepository;
import com.example.bank.media.persistence.MediaRecordingRepository;
import com.example.bank.media.persistence.MediaRoomRepository;
import com.example.bank.media.persistence.MediaUserRepository;
import com.example.bank.media.port.MediaEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MediaPersistenceTest {

    private JdbcTemplate jdbc;
    private MediaRoomRepository rooms;
    private MediaUserRepository users;
    private MediaParticipantRepository participants;
    private MediaRecordingRepository recordings;
    private MediaEventRepository events;
    private MediaIdentityResolver identity;
    private final MediaEventPublisher mediaEvents = mock(MediaEventPublisher.class);

    @BeforeEach
    void initializeSchema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:media-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        rooms = new MediaRoomRepository(jdbc);
        users = new MediaUserRepository(jdbc);
        participants = new MediaParticipantRepository(jdbc);
        recordings = new MediaRecordingRepository(jdbc);
        events = new MediaEventRepository(jdbc, new ObjectMapper());
        rooms.initialize();
        users.initialize();
        participants.initialize();
        recordings.initialize();
        events.initialize();
        identity = new MediaIdentityResolver(users);
    }

    private VideoRoomService roomService() {
        VideoRoomService service = new VideoRoomService(mediaEvents);
        service.setRoomRepository(rooms);
        service.setParticipantRepository(participants);
        service.setEventRepository(events);
        service.setIdentityResolver(identity);
        return service;
    }

    private Map<String, Object> context() {
        Map<String, Object> context = new HashMap<String, Object>();
        context.put("customer_id", "c1");
        context.put("agent_session_id", "a1");
        context.put("video_room_id", "placeholder");
        context.put("biz_key", "BIZ-1");
        context.put("biz_date", "2026-10-19");
        List<Map<String, Object>> members = new ArrayList<Map<String, Object>>();
        Map<String, Object> customer = new HashMap<String, Object>();
        customer.put("actorId", "c1");
        customer.put("role", "CUSTOMER");
        customer.put("peerId", "cust-peer");
        members.add(customer);
        Map<String, Object> agent = new HashMap<String, Object>();
        agent.put("actorId", "a1");
        agent.put("role", "AGENT");
        agent.put("peerId", "agent-peer");
        members.add(agent);
        context.put("call_participants", members);
        return context;
    }

    private MediaUserService userService() {
        MediaUserService service = new MediaUserService();
        service.setUserRepository(users);
        return service;
    }

    @Test
    void mediaUserIsRegisteredInDatabase() {
        Map<String, String> input = new HashMap<String, String>();
        input.put("role", "CUSTOMER");
        input.put("subject", "c1");
        input.put("subjectName", "客户一");
        Map<String, Object> result = userService().apply(input);
        String peerId = String.valueOf(result.get("peerId"));
        assertNotNull(users.findByPeerId(peerId));
        assertEquals("CUSTOMER", users.findByPeerId(peerId).get("subject_role"));
    }

    @Test
    void roomOpenInviteAndCloseArePersistedWithEvents() {
        VideoRoomService service = roomService();
        Map<String, Object> context = context();
        String roomId = service.open("p1", context);
        context.put("video_room_id", roomId);
        assertEquals("OPEN", rooms.find(roomId).get("state"));
        assertEquals("BIZ-1", rooms.find(roomId).get("biz_key"));
        assertEquals(1, events.listByRoom(roomId).size());

        assertEquals(roomId, service.open("p1", context));
        assertEquals(1, events.listByRoom(roomId).size());

        service.inviteParticipants("p1", context);
        assertEquals(2, participants.countActive(roomId));
        assertTrue(events.listByRoom(roomId).stream()
                .anyMatch(event -> "PARTICIPANT_JOINED".equals(event.get("event_type"))));

        Map<String, Object> close = new HashMap<String, Object>();
        close.put("roomId", roomId);
        service.close("p1", close);
        assertEquals("CLOSED", rooms.find(roomId).get("state"));
        assertEquals(0, participants.countActive(roomId));
        assertTrue(events.listByRoom(roomId).stream()
                .anyMatch(event -> "ROOM_CLOSED".equals(event.get("event_type"))));
    }

    @Test
    void recordingSessionStoresReceiptAndEvidenceReference() {
        VideoRoomService service = roomService();
        Map<String, Object> context = context();
        String roomId = service.open("p1", context);
        context.put("video_room_id", roomId);
        service.inviteParticipants("p1", context);

        MediaRecordingService recording = new MediaRecordingService(rooms, recordings, events);
        Map<String, Object> startBody = new HashMap<String, Object>();
        startBody.put("biz_key", "BIZ-1");
        startBody.put("biz_date", "2026-10-19");
        startBody.put("segment_seconds", "30");
        Map<String, Object> started = recording.start(roomId, startBody);
        String recordingId = String.valueOf(started.get("recordingId"));
        assertEquals("RECORDING", started.get("state"));
        assertEquals(30, started.get("segmentSeconds"));

        Map<String, Object> stopped = recording.stop(recordingId);
        assertEquals("STOPPING", stopped.get("state"));

        Map<String, Object> receiptBody = new HashMap<String, Object>();
        receiptBody.put("state", "SEALED");
        receiptBody.put("evidence_ref", "EV-1");
        receiptBody.put("segment_count", "3");
        receiptBody.put("total_bytes", "1024");
        Map<String, Object> sealed = recording.receipt(recordingId, receiptBody);
        assertEquals("SEALED", sealed.get("state"));
        assertEquals("EV-1", sealed.get("evidenceRef"));
        assertEquals(3, sealed.get("segmentCount"));
        assertEquals(1024L, sealed.get("totalBytes"));
        assertTrue(events.listByRecording(recordingId).stream()
                .anyMatch(event -> "RECORDING_SEALED".equals(event.get("event_type"))));
    }

    @Test
    void participantServiceRecordsJoinAndLeave() {
        VideoRoomService service = roomService();
        Map<String, Object> context = context();
        String roomId = service.open("p1", context);
        context.put("video_room_id", roomId);
        service.inviteParticipants("p1", context);

        RoomParticipantService participantService = new RoomParticipantService(mediaEvents);
        participantService.setParticipantRepository(participants);
        participantService.setEventRepository(events);
        participantService.setRoomRepository(rooms);
        participantService.setIdentityResolver(identity);

        Map<String, Object> update = new HashMap<String, Object>();
        update.put("roomId", roomId);
        update.put("joiningActorId", "a1");
        update.put("participants", Arrays.<Map<String, Object>>asList(
                member("a1", "AGENT", "agent-peer")));
        participantService.update(roomId, update);
        assertNotNull(participants.find(roomId, "a1"));

        Map<String, Object> leave = new HashMap<String, Object>();
        leave.put("roomId", roomId);
        leave.put("participants", Arrays.<Map<String, Object>>asList(
                member("a1", "AGENT", "agent-peer")));
        leave.put("leavingMember", member("a1", "AGENT", "agent-peer"));
        participantService.update(roomId, leave);
        assertEquals("LEFT", participants.find(roomId, "a1").get("state"));
        assertTrue(events.listByRoom(roomId).stream()
                .anyMatch(event -> "PARTICIPANT_LEFT".equals(event.get("event_type"))));
    }

    private Map<String, Object> member(String actorId, String role, String peerId) {
        Map<String, Object> member = new HashMap<String, Object>();
        member.put("actorId", actorId);
        member.put("role", role);
        member.put("peerId", peerId);
        return member;
    }
}

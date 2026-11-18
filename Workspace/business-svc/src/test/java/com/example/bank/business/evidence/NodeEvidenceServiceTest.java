package com.example.bank.business.evidence;
import com.example.bank.business.port.ProcessPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class NodeEvidenceServiceTest {
    @Test void nodePolicyControlsPhotoOwnershipAndAuthorizationProof()throws Exception {
        JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:evidence-node;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));ObjectMapper json=new ObjectMapper();InteractionTraceService traces=new InteractionTraceService(jdbc,json);traces.initialize();
        ProcessPort process=mock(ProcessPort.class);PhotoEvidenceService photos=mock(PhotoEvidenceService.class);NodeEvidenceService service=new NodeEvidenceService(process,photos,jdbc,json);
        Map<String,Object> state=new HashMap<>(),policy=new HashMap<>();state.put("processId","p1");state.put("bizNo","BIZ-N");state.put("nodeName","人工授权");state.put("processStatus","RUNNING");state.put("vars",Collections.singletonMap("agent_session_id","agent"));
        policy.put("actors",Collections.singletonList("AGENT"));policy.put("action","AUTHORIZE");policy.put("evidence",new HashMap<String,Object>(){{put("required",true);put("kind","HANDHELD_ID");}});state.put("nodePolicy",policy);when(process.state("p1")).thenReturn(state);
        MockMultipartFile file=new MockMultipartFile("file","photo.png","image/png",new byte[]{1});
        assertThrows(ResponseStatusException.class,()->service.upload(file,"p1","other","AGENT","witness_photo","photo-1"));verifyNoInteractions(photos);
        Map<String,Object> request=new HashMap<>();request.put("agentId","agent");request.put("action","AUTHORIZE");request.put("payload",Collections.singletonMap("evidence_receipt","photo-1"));assertThrows(ResponseStatusException.class,()->service.verifySubmission(state,request));
        Map<String,Object> proof=new HashMap<>();proof.put("actorId","agent");proof.put("kind","HANDHELD_ID");proof.put("fileUrl","/records/test.png");traces.append("BIZ-N","photo-1","p1","人工授权","AGENT","CAPTURE_SAVED",proof,"");
        assertDoesNotThrow(()->service.verifySubmission(state,request));
        request.put("agentId","other");assertThrows(ResponseStatusException.class,()->service.verifySubmission(state,request));
    }
}

package com.example.bank.business.service;
import com.example.bank.business.evidence.*;
import com.example.bank.business.port.ProcessPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PasswordResetServiceTest {
    @Test void databaseThresholdChangesManualEligibilityAndSurvivesInitialization(){
        Fixture f=new Fixture();Map<String,Object> result=f.service.identity(f.input());assertEquals(0,new java.math.BigDecimal("90").compareTo((java.math.BigDecimal)result.get("faceScore")));assertEquals(0,new java.math.BigDecimal("90").compareTo((java.math.BigDecimal)result.get("faceThreshold")));
        assertEquals("演示客户",((Map<?,?>)result.get("idFrontOcr")).get("name"));assertNotNull(((Map<?,?>)result.get("idBackOcr")).get("issuer"));assertNotNull(result.get("onlineResult"));
        f.jdbc.update("UPDATE biz_verification_policy SET threshold=91 WHERE policy_code='PASSWORD_RESET_FACE'");f.service.initialize();
        Map<String,Object> manual=f.service.identity(f.input());assertEquals(0,new java.math.BigDecimal("90").compareTo((java.math.BigDecimal)manual.get("faceScore")));assertEquals(0,new java.math.BigDecimal("91").compareTo((java.math.BigDecimal)manual.get("faceThreshold")));
        Map<String,Object> reset=f.input();reset.put("identityAuditId",manual.get("identityAuditId"));assertThrows(ResponseStatusException.class,()->f.service.reset(reset));reset.put("humanConfirmed",true);assertThrows(ResponseStatusException.class,()->f.service.reset(reset));reset.put("authorizationId","approved-auth");assertEquals("SUCCESS",f.service.reset(reset).get("resetStatus"));verify(f.sms).requireVerified(reset);verify(f.docs).requireSigned(reset);
    }
    @Test void ocrMismatchIsExplicitAndUnrecordedPhotosAreRejected(){Fixture f=new Fixture();Map<String,Object> request=f.input();request.put("mockOcrCardNo","6222029999999999");assertEquals(false,f.service.mediaOcr(request).get("mediaMatched"));request=f.input();request.put("mediaPhoto","/records/forged.png");Map<String,Object> forged=request;assertThrows(ResponseStatusException.class,()->f.service.mediaOcr(forged));}
    @Test void onlyOwnedCardsAreAccepted(){Fixture f=new Fixture();Map<String,Object> request=f.input();request.put("cardId","OTHER-CARD");assertThrows(ResponseStatusException.class,()->f.service.mediaOcr(request));}
    @Test void customerCaptureRequiresCustomerEvidenceAndDefaultsToManualReview() {
        Fixture f = new Fixture();
        Map<String,Object> input = f.input();
        input.remove("facePhoto");
        input.put("customerFacePhoto", "/records/FACE.png");
        assertThrows(ResponseStatusException.class, () -> f.service.identity(input));
        String photoUrl = "/records/customer-face.png";
        f.trace.append("BIZ-P","customer-face","p1","客户采集人脸","CUSTOMER","CAPTURE_SAVED",
                new LinkedHashMap<String,Object>() {{ put("fileUrl",photoUrl); put("kind","FACE"); }}, "");
        input.put("customerFacePhoto", photoUrl);
        Map<String,Object> result = f.service.identity(input);
        assertEquals(0, new java.math.BigDecimal("90").compareTo((java.math.BigDecimal)result.get("faceScore")));
        assertEquals(0, new java.math.BigDecimal("91").compareTo((java.math.BigDecimal)result.get("faceThreshold")));
        assertEquals(Boolean.FALSE, result.get("identityPassed"));
    }
    static class Fixture {
        final JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:password-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        final ObjectMapper json=new ObjectMapper();final InteractionTraceService trace=new InteractionTraceService(jdbc,json);final PasswordResetService service;
        final SmsVerificationService sms=mock(SmsVerificationService.class);final PasswordDocumentService docs=mock(PasswordDocumentService.class);
        Fixture(){trace.initialize();service=new PasswordResetService(jdbc,json,trace);org.springframework.test.util.ReflectionTestUtils.setField(service,"sms",sms);org.springframework.test.util.ReflectionTestUtils.setField(service,"documents",docs);org.springframework.test.util.ReflectionTestUtils.setField(service,"authorizations",mock(com.example.bank.business.application.AuthorizationService.class));service.initialize();for(String kind:Arrays.asList("MEDIA","ID_FRONT","ID_BACK","FACE")){Map<String,Object> payload=new HashMap<>();payload.put("fileUrl","/records/"+kind+".png");payload.put("kind",kind);payload.put("actorId","peer");trace.append("BIZ-P",kind,"p1","photo","FACE".equals(kind)?"AGENT":"CUSTOMER","CAPTURE_SAVED",payload,"");}}
        Map<String,Object> input(){Map<String,Object> value=new HashMap<>();value.put("customerId","DEMO-001");value.put("cardId","CARD-001");value.put("bizNo","BIZ-P");value.put("processId","p1");value.put("operationId",UUID.randomUUID().toString());value.put("mediaPhoto","/records/MEDIA.png");value.put("idFrontPhoto","/records/ID_FRONT.png");value.put("idBackPhoto","/records/ID_BACK.png");value.put("facePhoto","/records/FACE.png");return value;}
    }
}

package com.example.bank.business.evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TransactionDocumentServiceTest {
    @Test void signatureMustBeArchivedAndBoundToTheTermsBeforeTransferAndReceipt()throws Exception{
        JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:docs-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));ObjectMapper json=new ObjectMapper();InteractionTraceService traces=new InteractionTraceService(jdbc,json);traces.initialize();TransactionDocumentService documents=new TransactionDocumentService(jdbc,json,traces);documents.initialize();
        jdbc.execute("CREATE TABLE capability_receipt(operation_id VARCHAR(240) PRIMARY KEY,capability VARCHAR(100),request_json MEDIUMTEXT,status VARCHAR(20),response_json MEDIUMTEXT)");
        Map<String,Object> input=new LinkedHashMap<>();input.put("bizNo","VERIFY-DOC-"+UUID.randomUUID());input.put("processId","p-doc");input.put("amount",1000);input.put("payeeName","演示收款人");input.put("payeeAccount","6222021000123456");input.put("purpose","测试");
        assertThrows(ResponseStatusException.class,()->documents.requireSigned(input));
        BufferedImage image=new BufferedImage(800,300,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,800,300);g.setColor(Color.BLACK);g.setStroke(new BasicStroke(5));g.drawLine(100,100,500,160);g.drawLine(220,60,180,230);g.dispose();ByteArrayOutputStream png=new ByteArrayOutputStream();ImageIO.write(image,"png",png);
        Map<String,Object> context=Collections.singletonMap("signedTerms",TransactionDocumentService.terms(input));Map<String,Object> photo=new PhotoEvidenceService(traces).save(new MockMultipartFile("file","sign.png","image/png",png.toByteArray()),String.valueOf(input.get("bizNo")),"signature-test","p-doc","客户签字","cust-doc","CUSTOMER","CUSTOMER_SIGNATURE",context);
        BufferedImage blank=new BufferedImage(800,300,BufferedImage.TYPE_INT_RGB);Graphics2D clear=blank.createGraphics();clear.setColor(Color.WHITE);clear.fillRect(0,0,800,300);clear.dispose();ByteArrayOutputStream blankBytes=new ByteArrayOutputStream();ImageIO.write(blank,"png",blankBytes);
        Map<String,Object> blankPhoto=new PhotoEvidenceService(traces).save(new MockMultipartFile("file","blank.png","image/png",blankBytes.toByteArray()),String.valueOf(input.get("bizNo")),"blank-signature-test","p-doc","客户签字","cust-doc","CUSTOMER","CUSTOMER_SIGNATURE",context);
        Map<String,Object> invalid=new LinkedHashMap<>(input);invalid.put("signatureImage",blankPhoto.get("fileUrl"));assertThrows(ResponseStatusException.class,()->documents.archiveSignature(invalid));
        input.put("signatureImage",photo.get("fileUrl"));Map<String,Object> signed=documents.archiveSignature(input);input.putAll(signed);documents.requireSigned(input);
        String signatureId=String.valueOf(signed.get("signatureDocumentId"));assertTrue(signatureId.startsWith("signed-"));assertEquals(signed,documents.archiveSignature(input));
        Map<String,Object> changed=new LinkedHashMap<>(input);changed.put("amount",1001);assertThrows(ResponseStatusException.class,()->documents.requireSigned(changed));
        input.put("coreTxId","TX-DOC");assertThrows(ResponseStatusException.class,()->documents.receipt(input));
        Map<String,Object> response=Collections.singletonMap("data",Collections.singletonMap("coreTxId","TX-DOC"));jdbc.update("INSERT INTO capability_receipt VALUES(?,? ,?,'SUCCEEDED',?)","test-op","core.transfer.v1",json.writeValueAsString(input),json.writeValueAsString(response));
        Map<String,Object> receipt=documents.receipt(input);assertEquals(receipt,documents.receipt(input));assertEquals(4,traces.images(String.valueOf(input.get("bizNo"))).size());assertTrue(documents.archiveComplete(String.valueOf(input.get("bizNo"))));
        for(Map<String,Object> doc:Arrays.asList(signed,receipt)){Object url=doc.getOrDefault("signatureDocumentUrl",doc.get("receiptPdfUrl"));byte[] bytes=Files.readAllBytes(Paths.get("records").resolve(String.valueOf(url).substring(9)));assertTrue(new String(bytes,0,8,java.nio.charset.StandardCharsets.US_ASCII).startsWith("%PDF-1.4"));assertTrue(bytes.length>1000);}
    }
}

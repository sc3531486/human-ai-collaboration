package com.example.bank.business.evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.imageio.ImageIO;

/** 密码重置办理单、签字单及回执，复用公共PDF影像封装及影像归档表。 */
@Service
public class PasswordDocumentService {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final InteractionTraceService traces;
    public PasswordDocumentService(JdbcTemplate jdbc,ObjectMapper json,InteractionTraceService traces){this.jdbc=jdbc;this.json=json;this.traces=traces;}
    public Map<String,Object> form(Map<String,Object> input){
        Map<String,Object> terms=new LinkedHashMap<>();for(String key:Arrays.asList("bizNo","processId","customerId","cardId","identityAuditId","smsVerificationId"))terms.put(key,required(input,key));
        String id="PWD-FORM-"+digest(required(input,"operationId").getBytes(StandardCharsets.UTF_8));
        terms.put("customerName",required(input,"customerName"));terms.put("serviceFormId",id);return archive(id,"PASSWORD_FORM","密码重置业务办理单",terms,null,input);
    }
    public Map<String,Object> signature(Map<String,Object> input){
        if(!Boolean.TRUE.equals(input.get("confirmed")))bad("请坐席核实签名后确认提交");
        String formId=required(input,"serviceFormId");Map<String,Object> form=document(formId,"PASSWORD_FORM",input);
        Map<String,Object> terms=decode(form.get("terms_json"));String image=required(input,"signatureImage");Map<String,Object> capture=null;
        for(Map<String,Object> row:jdbc.queryForList("SELECT event_payload FROM biz_interaction_trace WHERE biz_no=? AND process_id=? AND actor_role='CUSTOMER' AND event_type='CAPTURE_SAVED'",input.get("bizNo"),input.get("processId"))){Map<String,Object> payload=decode(row.get("event_payload"));if("CUSTOMER_SIGNATURE".equals(payload.get("kind"))&&image.equals(payload.get("fileUrl")))capture=payload;}
        if(capture==null)bad("签名图片未归档");
        Map<String,Object> scope=new TreeMap<>();scope.put("serviceFormId",formId);scope.put("selected_card_id",terms.get("cardId"));scope.put("customer_id",terms.get("customerId"));scope.put("identityAuditId",terms.get("identityAuditId"));scope.put("smsVerificationId",terms.get("smsVerificationId"));
        if(!encode(scope).equals(encode(new TreeMap<>(decode(jsonString(capture.get("signedTerms")))))))bad("签名不属于当前办理单，请客户重新签字");
        BufferedImage signature;
        try{byte[] bytes=Files.readAllBytes(local(image));if(!digest(bytes).equals(capture.get("fileHash")))bad("签字影像校验失败");signature=ImageIO.read(new java.io.ByteArrayInputStream(bytes));}catch(java.io.IOException e){throw new IllegalStateException(e);}
        if(signature==null||signature.getWidth()<100)bad("签名影像无效");
        int ink=0;for(int y=0;y<signature.getHeight();y++)for(int x=0;x<signature.getWidth();x++){int p=signature.getRGB(x,y);if((p>>>24)>30&&((p>>16)&255)<180&&(p&255)<180)ink++;}if(ink<50)bad("请填写有效签字");
        terms.put("signatureReviewer",required(input,"agentId"));return archive("PWD-SIGN-"+digest((formId+image).getBytes(StandardCharsets.UTF_8)),"PASSWORD_SIGNATURE","密码重置客户签字单",terms,signature,input);
    }
    public void requireSigned(Map<String,Object> input){Map<String,Object> row=document(required(input,"signatureDocumentId"),"PASSWORD_SIGNATURE",input);Map<String,Object> terms=decode(row.get("terms_json"));for(String key:Arrays.asList("customerId","cardId","identityAuditId","smsVerificationId"))if(!Objects.equals(terms.get(key),input.get(key)))bad("签字单与当前业务要素不一致");verifyFile(row);}
    public Map<String,Object> receipt(Map<String,Object> input){requireSigned(input);Map<String,Object> signed=document(required(input,"signatureDocumentId"),"PASSWORD_SIGNATURE",input);Map<String,Object> terms=decode(signed.get("terms_json"));terms.put("resetReceipt",required(input,"resetReceipt"));terms.put("resetStatus",input.get("resetStatus"));if(!"SUCCESS".equals(input.get("resetStatus")))bad("重置未成功，不能生成成功回执");return archive("PWD-RECEIPT-"+digest(required(input,"resetReceipt").getBytes(StandardCharsets.UTF_8)),"PASSWORD_RECEIPT","密码重置业务回执",terms,null,input);}
    private Map<String,Object> document(String id,String kind,Map<String,Object> input){java.util.List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM biz_transaction_document WHERE document_id=? AND document_type=? AND biz_no=? AND process_id=?",id,kind,input.get("bizNo"),input.get("processId"));if(rows.size()!=1)bad("业务单据不存在或不属于当前业务");return rows.get(0);}
    private synchronized Map<String,Object> archive(String id,String kind,String title,Map<String,Object> terms,BufferedImage signature,Map<String,Object> input){
        java.util.List<Map<String,Object>> old=jdbc.queryForList("SELECT * FROM biz_transaction_document WHERE document_id=?",id);if(!old.isEmpty()){Map<String,Object> stored=document(id,kind,input);if(!encode(terms).equals(stored.get("terms_json")))bad("同一单据编号不能对应不同办理内容");verifyFile(stored);return result(id,kind,String.valueOf(stored.get("file_url")));}
        BufferedImage page=new BufferedImage(1000,1414,BufferedImage.TYPE_INT_RGB);Graphics2D g=page.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,1000,1414);g.setColor(new Color(22,55,92));g.setFont(new Font("Microsoft YaHei",Font.BOLD,30));g.drawString(title,70,100);g.setFont(new Font("Microsoft YaHei",Font.PLAIN,18));int y=170;Map<String,String> labels=new LinkedHashMap<>();labels.put("bizNo","业务流水");labels.put("customerName","客户姓名");labels.put("cardId","办理银行卡标识");labels.put("signatureReviewer","核实坐席");labels.put("resetReceipt","回执编号");labels.put("resetStatus","办理结果");for(Map.Entry<String,String> field:labels.entrySet()){Object value=terms.get(field.getKey());if(value==null)continue;String text=field.getValue()+"："+("SUCCESS".equals(value)?"密码重置成功":value);for(int offset=0;offset<text.length();offset+=50){g.drawString(text.substring(offset,Math.min(text.length(),offset+50)),70,y);y+=32;}y+=10;}if(signature!=null){g.drawString("本人已核对办理单，同意办理本次密码重置。",70,y+20);g.drawImage(signature,70,y+45,760,220,null);}g.drawString("演示业务（Mock）· 非真实银行凭证",70,1340);g.dispose();
        try{byte[] pdf=ImagePdf.encode(page);Path dir=Paths.get("records").toAbsolutePath();Files.createDirectories(dir);Path path=dir.resolve(id+".pdf");try{Files.write(path,pdf,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException existing){if(!digest(Files.readAllBytes(path)).equals(digest(pdf)))bad("归档单据不能覆盖");}String url="/records/"+path.getFileName();jdbc.update("INSERT INTO biz_transaction_document(document_id,biz_no,process_id,document_type,terms_json,file_url,file_hash) VALUES(?,?,?,?,?,?,?)",id,input.get("bizNo"),input.get("processId"),kind,encode(terms),url,digest(pdf));Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("documentId",id);evidence.put("fileUrl",url);evidence.put("kind",kind);evidence.put("fileHash",digest(pdf));traces.append(required(input,"bizNo"),id,required(input,"processId"),String.valueOf(input.get("nodeName")),"SYSTEM","DOCUMENT_ARCHIVED",evidence,"");return result(id,kind,url);}catch(java.io.IOException e){throw new IllegalStateException("密码业务单据归档失败",e);}
    }
    private Map<String,Object> result(String id,String kind,String url){Map<String,Object> out=new LinkedHashMap<>();String key="PASSWORD_FORM".equals(kind)?"serviceForm":"PASSWORD_SIGNATURE".equals(kind)?"signatureDocument":"receiptPdf";out.put(key+"Id",id);out.put(key+"Url",url);return out;}
    private void verifyFile(Map<String,Object> row){try{if(!digest(Files.readAllBytes(local(String.valueOf(row.get("file_url"))))).equals(row.get("file_hash")))bad("归档单据校验失败");}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    private Path local(String url){if(!url.matches("/records/[A-Za-z0-9_.-]+"))bad("无效影像地址");return Paths.get("records").toAbsolutePath().resolve(url.substring(9));}
    private String required(Map<String,Object> input,String key){String value=String.valueOf(input.getOrDefault(key,""));if(value.trim().isEmpty()||"null".equals(value))bad("缺少办理单参数："+key);return value;}
    private Map<String,Object> decode(Object value){try{return json.readValue(String.valueOf(value),Map.class);}catch(Exception e){throw new IllegalStateException(e);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String jsonString(Object value){return value instanceof Map?encode(value):String.valueOf(value);}
    private String digest(byte[] value){try{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value))out.append(String.format("%02x",b));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}

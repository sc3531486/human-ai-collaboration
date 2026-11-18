package com.example.bank.business.evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.math.BigDecimal;
/** 签字单及转账回执的业务接口；流程编排仍由 Flowable 负责。 */
@Service
public class TransactionDocumentService {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final InteractionTraceService traces;
    public TransactionDocumentService(JdbcTemplate jdbc,ObjectMapper json,InteractionTraceService traces){this.jdbc=jdbc;this.json=json;this.traces=traces;}
    @PostConstruct public void initialize(){jdbc.execute("CREATE TABLE IF NOT EXISTS biz_transaction_document(document_id VARCHAR(80) PRIMARY KEY,biz_no VARCHAR(64) NOT NULL,process_id VARCHAR(64) NOT NULL,document_type VARCHAR(40) NOT NULL,terms_json MEDIUMTEXT NOT NULL,file_url VARCHAR(255) NOT NULL,file_hash VARCHAR(64) NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");}
    public static Map<String,Object> terms(Map<String,Object> input){
        Map<String,Object> terms=new TreeMap<>();for(String key:Arrays.asList("bizNo","processId","payeeName","payeeAccount","purpose"))terms.put(key,String.valueOf(input.getOrDefault(key,"")));
        try{BigDecimal amount=new BigDecimal(String.valueOf(input.get("amount")));if(amount.signum()<=0||amount.scale()>2)bad("转账金额必须大于零且最多两位小数");terms.put("amount",amount.stripTrailingZeros().toPlainString());}catch(NumberFormatException e){bad("转账金额无效");}
        return terms;
    }
    public Map<String,Object> archiveSignature(Map<String,Object> input){
        Map<String,Object> expected=terms(input);String url=String.valueOf(input.get("signatureImage"));Map<String,Object> capture=null;
        for(Map<String,Object> row:jdbc.queryForList("SELECT event_payload FROM biz_interaction_trace WHERE biz_no=? AND process_id=? AND actor_role='CUSTOMER' AND event_type='CAPTURE_SAVED'",input.get("bizNo"),input.get("processId"))){Map<String,Object> payload=decode(row.get("event_payload"));if("CUSTOMER_SIGNATURE".equals(payload.get("kind"))&&url.equals(payload.get("fileUrl")))capture=payload;}
        if(capture==null||!encode(expected).equals(encode(capture.get("signedTerms"))))bad("客户签字与当前交易要素不匹配，请重新签字");
        BufferedImage signature;
        try{byte[] bytes=Files.readAllBytes(localFile(url));if(!hash(bytes).equals(capture.get("fileHash")))bad("签字图片校验失败，请重新签字");signature=ImageIO.read(new java.io.ByteArrayInputStream(bytes));}catch(Exception e){throw new IllegalStateException("签字图片无法读取",e);}
        if(signature==null||signature.getWidth()<100||signature.getHeight()<50)bad("签字图片无效");
        int ink=0;for(int y=0;y<signature.getHeight();y++)for(int x=0;x<signature.getWidth();x++){int pixel=signature.getRGB(x,y);if((pixel>>>24)>30&&((pixel>>16)&255)<180&&(pixel&255)<180)ink++;}
        if(ink<50)bad("签字内容为空，请客户重新签字");
        return archive("SIGNATURE_SHEET",expected,signature,input,null);
    }
    public void requireSigned(Map<String,Object> input){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM biz_transaction_document WHERE document_id=? AND document_type='SIGNATURE_SHEET' AND biz_no=? AND process_id=?",input.get("signatureDocumentId"),input.get("bizNo"),input.get("processId"));
        if(rows.size()!=1||!encode(terms(input)).equals(String.valueOf(rows.get(0).get("terms_json"))))bad("签字单尚未归档或交易要素已变化，禁止正式转账");
        try{byte[] bytes=Files.readAllBytes(localFile(String.valueOf(rows.get(0).get("file_url"))));if(!hash(bytes).equals(rows.get(0).get("file_hash")))bad("归档签字单校验失败");}catch(java.io.IOException e){throw new IllegalStateException("签字单未能读取，禁止正式转账",e);}
    }
    public Map<String,Object> receipt(Map<String,Object> input){
        requireSigned(input);String tx=String.valueOf(input.getOrDefault("coreTxId",""));boolean found=false;
        for(Map<String,Object> row:jdbc.queryForList("SELECT request_json,response_json FROM capability_receipt WHERE capability='core.transfer.v1' AND status='SUCCEEDED'")){
            Map<String,Object> request=decode(row.get("request_json")),response=decode(row.get("response_json"));Object data=response.get("data");
            if(encode(terms(request)).equals(encode(terms(input)))&&data instanceof Map&&tx.equals(((Map<?,?>)data).get("coreTxId")))found=true;
        }
        if(!found||tx.isEmpty())bad("未找到正式转账成功回执，不能生成交易PDF");
        return archive("TRANSFER_RECEIPT",terms(input),null,input,tx);
    }
    private Map<String,Object> archive(String kind,Map<String,Object> terms,BufferedImage signature,Map<String,Object> input,String tx){
        String id=(signature==null?"receipt-":"signed-")+hash((encode(terms)+":"+String.valueOf(input.get("signatureImage"))+":"+tx).getBytes(StandardCharsets.UTF_8));
        List<Map<String,Object>> existing=jdbc.queryForList("SELECT * FROM biz_transaction_document WHERE document_id=?",id);if(!existing.isEmpty())return result(id,String.valueOf(existing.get(0).get("file_url")),kind);
        BufferedImage page=new BufferedImage(1000,1414,BufferedImage.TYPE_INT_RGB);Graphics2D g=page.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,1000,1414);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new Color(22,55,92));g.setFont(new Font("Microsoft YaHei",Font.BOLD,32));g.drawString(signature==null?"转账业务回执":"转账业务客户签字确认单",70,100);g.setFont(new Font("Microsoft YaHei",Font.PLAIN,22));
        int y=180;String[] labels={"业务流水","收款人姓名","收款账号","转账金额（人民币元）","用途"};String[] keys={"bizNo","payeeName","payeeAccount","amount","purpose"};
        for(int i=0;i<keys.length;i++){g.drawString(labels[i]+"：",70,y);y+=38;String text=String.valueOf(terms.get(keys[i]));for(int start=0;start<text.length();start+=42){g.drawString(text.substring(start,Math.min(text.length(),start+42)),90,y);y+=34;}y+=26;}
        if(signature!=null){g.drawString("本人已核对上述交易要素，同意按该金额及收款账号办理转账。",70,y+20);g.drawString("客户手写签字：",70,y+80);g.drawImage(signature,90,y+100,750,220,null);}
        else{g.drawString("核心交易流水："+tx,70,y+25);g.drawString("办理结果：转账成功",70,y+80);}
        g.setFont(new Font("Microsoft YaHei",Font.PLAIN,18));g.drawString("归档时间："+java.time.LocalDateTime.now().toString().replace('T',' '),70,1290);g.drawString("演示交易（Mock）· 非真实银行凭证",70,1330);g.dispose();
        try{byte[] pdf=ImagePdf.encode(page);Path dir=Paths.get("records").toAbsolutePath();Files.createDirectories(dir);Path file=dir.resolve(id+".pdf");try{Files.write(file,pdf,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException e){pdf=Files.readAllBytes(file);}String url="/records/"+file.getFileName();String digest=hash(pdf);jdbc.update("INSERT INTO biz_transaction_document(document_id,biz_no,process_id,document_type,terms_json,file_url,file_hash) VALUES(?,?,?,?,?,?,?)",id,terms.get("bizNo"),terms.get("processId"),kind,encode(terms),url,digest);
            Map<String,Object> payload=new LinkedHashMap<>();payload.put("fileUrl",url);payload.put("fileHash",digest);payload.put("kind",kind);payload.put("documentId",id);payload.put("fileSize",pdf.length);traces.append(String.valueOf(terms.get("bizNo")),id,String.valueOf(terms.get("processId")),String.valueOf(input.getOrDefault("nodeName","归档业务单据")),"SYSTEM","DOCUMENT_ARCHIVED",payload,"");return result(id,url,kind);
        }catch(Exception e){throw new IllegalStateException("业务单据归档失败，流程不能继续",e);}
    }
    public boolean archiveComplete(String bizNo){
        Set<String> kinds=new HashSet<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT document_type,file_url,file_hash FROM biz_transaction_document WHERE biz_no=?",bizNo)){
            try{if(hash(Files.readAllBytes(localFile(String.valueOf(row.get("file_url"))))).equals(row.get("file_hash")))kinds.add(String.valueOf(row.get("document_type")));}catch(Exception missing){return false;}
        }
        return kinds.containsAll(Arrays.asList("SIGNATURE_SHEET","TRANSFER_RECEIPT"));
    }
    private Map<String,Object> result(String id,String url,String kind){Map<String,Object> result=new LinkedHashMap<>();result.put("SIGNATURE_SHEET".equals(kind)?"signatureDocumentId":"receiptDocumentId",id);result.put("SIGNATURE_SHEET".equals(kind)?"signatureDocumentUrl":"receiptPdfUrl",url);return result;}
    private Path localFile(String url){if(!url.matches("/records/[A-Za-z0-9_.-]+"))bad("无效的业务影像地址");return Paths.get("records").toAbsolutePath().resolve(url.substring(9));}
    private Map<String,Object> decode(Object value){try{return json.readValue(String.valueOf(value),Map.class);}catch(Exception e){throw new IllegalStateException("业务单据元数据无效",e);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private String hash(byte[] value){try{StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value))result.append(String.format("%02x",b));return result.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}

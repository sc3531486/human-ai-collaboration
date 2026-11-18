package com.example.bank.business.evidence;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Service
public class PhotoEvidenceService {
    private final InteractionTraceService traces;
    public PhotoEvidenceService(InteractionTraceService traces){this.traces=traces;}
    public Map<String,Object> save(MultipartFile file,String bizNo,String eventId,String processId,String nodeName,String actor,String role,String kind) throws Exception {
        return save(file,bizNo,eventId,processId,nodeName,actor,role,kind,Collections.emptyMap());
    }
    public Map<String,Object> save(MultipartFile file,String bizNo,String eventId,String processId,String nodeName,String actor,String role,String kind,Map<String,Object> context)throws Exception {
        traces.identifier(bizNo,64);traces.identifier(eventId,100);
        if(file.isEmpty() || file.getSize()>8*1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"照片为空或超过8 MB");
        byte[] bytes=file.getBytes(),signature=new byte[]{(byte)137,80,78,71,13,10,26,10};
        if(bytes.length<8 || !Arrays.equals(signature,Arrays.copyOf(bytes,8)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"照片必须为PNG格式");
        String name="capture-"+hash((bizNo+":"+eventId).getBytes(java.nio.charset.StandardCharsets.UTF_8))+".png";
        Path directory=Paths.get("records").toAbsolutePath().normalize();Files.createDirectories(directory);Path target=directory.resolve(name);
        try{Files.write(target,bytes,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException e){if(!hash(Files.readAllBytes(target)).equals(hash(bytes)))throw new ResponseStatusException(HttpStatus.CONFLICT,"同一照片编号不能覆盖原照片");}
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("fileUrl","/records/"+name);payload.put("fileHash",hash(bytes));payload.put("fileSize",bytes.length);payload.put("kind",kind);payload.put("actorId",actor);
        payload.putAll(context);payload.put("eventId",eventId);traces.append(bizNo,eventId,processId,nodeName,role,"CAPTURE_SAVED",payload,"");payload.put("success",true);return payload;
    }
    private String hash(byte[] bytes)throws Exception{StringBuilder value=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))value.append(String.format("%02x",b));return value.toString();}
}

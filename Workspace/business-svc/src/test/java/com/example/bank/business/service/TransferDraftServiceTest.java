package com.example.bank.business.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TransferDraftServiceTest {
    private TransferDraftService service(){TransferDraftService service=new TransferDraftService(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:transfer-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","")),new ObjectMapper());service.initialize();return service;}
    private Map<String,Object> input(String text){Map<String,Object> value=new LinkedHashMap<>();value.put("text",text);value.put("bizNo","BIZ-T");value.put("processId","");return value;}
    private Map<String,Object> extracted(){Map<String,Object> value=new LinkedHashMap<>();value.put("intent","TRANSFER");value.put("payee","王总");value.put("amount",2000000);value.put("slotsComplete",true);value.put("payee_account","6222029999999999");return value;}
    @Test void missingAccountIsPursuedEvenIfTheModelClaimsCompleteOrInventsOne(){Map<String,Object> result=service().complete(input("给王总转账200w"),extracted());assertEquals(false,result.get("slotsComplete"));assertEquals("SUBMIT",result.get("action"));assertEquals("advanced_sop",result.get("processKey"));assertFalse(((Map<?,?>)result.get("startVars")).containsKey("payee_account"));assertTrue(String.valueOf(result.get("reply")).contains("收款账号"));assertEquals(new java.math.BigDecimal("2000000"),result.get("amount"));}
    @Test void accountOnlyReplyCompletesThePersistedDraft(){TransferDraftService service=service();service.complete(input("给王总转账200w"),extracted());Map<String,Object> result=service.complete(input("账号6222021000123456"),Collections.singletonMap("intent","QA"));assertEquals(true,result.get("slotsComplete"));assertEquals("SUBMIT",result.get("action"));assertEquals("王总",result.get("payee"));assertEquals("6222021000123456",result.get("payee_account"));}
    @Test void decimalAmountIsPreserved(){Map<String,Object> result=service().complete(input("给王总转账12.34元，账号6222021000123456"),extracted());assertEquals(new java.math.BigDecimal("12.34"),result.get("amount"));}
}

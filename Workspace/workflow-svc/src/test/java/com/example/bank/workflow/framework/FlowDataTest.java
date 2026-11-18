package com.example.bank.workflow.framework;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FlowDataTest {
    private final FlowData data=new FlowData(new ObjectMapper());
    @Test void jsonTextAndNestedPatchKeepPreviousFields(){
        Map<String,Object> before=data.object("{\"application\":{\"name\":\"张三\",\"items\":[1,2]},\"amount\":\"600000.01\"}");
        Map<String,Object> patch=data.object("{\"application\":{\"items\":[3]}}");
        Map<String,Object> merged=data.merge(before,patch);
        assertEquals("张三",data.read(merged,"application.name"));
        assertEquals(Collections.singletonList(3),data.read(merged,"application.items"));
        assertEquals("600000.01",merged.get("amount"));
    }
    @Test void rejectMalformedAndAmbiguousInput(){
        assertThrows(IllegalArgumentException.class,()->data.object("[1,2]"));
        assertThrows(IllegalArgumentException.class,()->data.object("{broken}"));
        assertThrows(IllegalArgumentException.class,()->data.object("{\"__proto__\":{}}"));
        NodeSubmission request=new NodeSubmission();request.payload="{}";request.values=Collections.emptyMap();
        assertThrows(IllegalArgumentException.class,()->request.resolveValues(data));
    }
    @Test void nestedKeysHaveStableCanonicalOrder(){
        assertEquals(data.encode(data.canonical(data.object("{\"a\":{\"x\":1,\"y\":2}}"))),
                data.encode(data.canonical(data.object("{\"a\":{\"y\":2,\"x\":1}}"))));
    }
}

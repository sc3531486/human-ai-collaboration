package com.example.bank.business.service;
import com.example.bank.business.capability.CapabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CapabilityCatalogTest {
    @Test void everyShippedDefinitionCanBeLoadedAtStartup()throws Exception{
        ObjectMapper mapper=new ObjectMapper();try(java.io.InputStream input=getClass().getResourceAsStream("/capabilities.json")){
            Map<String,Object> catalog=mapper.readValue(input,Map.class);assertTrue(catalog.containsKey("password.identity.v1"));
            catalog.forEach((code,value)->{CapabilityService.Definition definition=mapper.convertValue(value,CapabilityService.Definition.class);assertNotNull(definition.path,code);assertNotNull(definition.required,code);});
        }
    }
}

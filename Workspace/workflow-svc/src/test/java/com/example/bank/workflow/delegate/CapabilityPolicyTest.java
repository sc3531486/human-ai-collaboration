package com.example.bank.workflow.delegate;
import org.flowable.bpmn.model.*;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CapabilityPolicyTest {
    @Test void allShippedCapabilitiesAreConfiguredInBpmn()throws Exception{
        int count=0;
        for(String resource:new String[]{"AdvancedSOP","BalanceSOP","PasswordResetSOP","UnifiedAuthorizationSOP"})try(java.io.InputStream input=getClass().getResourceAsStream("/processes/"+resource+".bpmn20.xml")){
            javax.xml.stream.XMLStreamReader reader=javax.xml.stream.XMLInputFactory.newInstance().createXMLStreamReader(input);BpmnModel model=new org.flowable.bpmn.converter.BpmnXMLConverter().convertToBpmnModel(reader);reader.close();
            for(FlowElement element:model.getMainProcess().getFlowElements())if(element.getExtensionElements().containsKey("capability")){
                DelegateExecution execution=mock(DelegateExecution.class);when(execution.getCurrentFlowElement()).thenReturn(element);for(CapabilityPolicy policy:CapabilityPolicy.readAll(execution)){assertFalse(policy.code.isEmpty());assertFalse(policy.outputs.isEmpty());count++;}
            }
        }
        assertTrue(count>=11);
    }
    @Test void missingConfigIsRejectedInsteadOfUsingAnOldNodeName(){DelegateExecution execution=mock(DelegateExecution.class);ServiceTask node=new ServiceTask();node.setId("core_transfer");when(execution.getCurrentFlowElement()).thenReturn(node);assertThrows(IllegalStateException.class,()->CapabilityPolicy.read(execution));}
}

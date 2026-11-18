package com.example.bank.business.mock;

import com.example.bank.business.customer.CustomerProfile;
import com.example.bank.business.customer.CustomerProfileService;
import org.springframework.stereotype.Service;
import java.util.Map;

/** 渠道演示会话的客户画像在业务服务生成，流程与路由只消费显式字段。 */
@Service
public class MockCustomerContextService {
    private final CustomerProfileService profiles;
    public MockCustomerContextService(CustomerProfileService profiles) { this.profiles=profiles; }
    public void enrich(Map<String,Object> vars) {
        String customerId=String.valueOf(vars.get("customer_id"));
        CustomerProfile profile=profiles.find(customerId);
        if(profile==null) {
            profile=new CustomerProfile();
            profile.customerId=customerId;
            profile.name=String.valueOf(vars.getOrDefault("customer_name","演示客户"));
        }
        vars.put("customer_level",profile.level);
        vars.put("customer_level_name",profile.levelName);
        vars.put("customer_skill_group",profile.skillGroup);
        vars.put("customer_cert_masked",profile.certNoMasked);
        vars.put("customer_mobile_masked",profile.mobileMasked);
        vars.put("customer_risk_listed",profile.riskListed);
        vars.putIfAbsent("channel",profile.channel);
    }
}

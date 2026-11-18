package com.example.bank.routing.profile;

import java.util.ArrayList;
import java.util.List;

/** 客户档案：申请用户接口与等级识别子服务的结果载体，字段直接参与页面渲染。 */
public class CustomerProfile {
    public String customerId;
    public String name;
    public String certNo;
    public String certNoMasked;
    public String mobile;
    public String mobileMasked;
    public String channel;
    /** PRIVATE / GOLD / NORMAL */
    public String level;
    public String levelName;
    /** 队列路由所需的坐席技能组 */
    public String skillGroup;
    public int assetTier;
    public double monthlyVolume;
    public boolean riskListed;
    public List<String> tags = new ArrayList<String>();
    public long appliedAt = System.currentTimeMillis();
    public long identifiedAt;
}

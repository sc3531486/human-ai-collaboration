package com.example.bank.workflow.contract;

/**
 * 客户画像 —— 跨服务传输的<b>裁剪版</b>。
 *
 * <p>完整画像由 business-svc 持有。本服务只携带排队路由需要的那部分，
 * 取值全部来自进线时写入的流程变量，<b>不回调 business-svc 查询档案</b>：
 * 省一次 HTTP 往返，也让本服务不依赖客户档案。</p>
 *
 * <p>本服务不填充画像默认值；演示数据同样由业务服务显式提供。</p>
 */
public class CustomerProfile {
    public String customerId;
    public String name;
    public String certNoMasked;
    public String mobileMasked;
    public String channel;
    /** PRIVATE / GOLD / NORMAL */
    public String level;
    public String levelName;
    /** 队列路由所需的坐席技能组 */
    public String skillGroup;
    public boolean riskListed;
}

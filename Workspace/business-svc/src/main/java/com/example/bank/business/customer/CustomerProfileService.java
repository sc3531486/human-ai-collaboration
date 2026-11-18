package com.example.bank.business.customer;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户申请与画像子服务（原型为内置模拟实现）。
 * 申请用户接口负责建档；进线排队时会再次调用本服务的 identify 识别客户等级，
 * 真实项目可替换为行内客户画像 / 星级系统，调用方无需改动。
 */
@Service
public class CustomerProfileService {

    private final Map<String, CustomerProfile> profiles = new ConcurrentHashMap<String, CustomerProfile>();

    /** 模拟涉案 / 涉诈名单，命中后不参与高级别路由。 */
    private static final Map<String, String> RISK_LIST = new HashMap<String, String>();
    static {
        RISK_LIST.put("610923199710250118", "涉案账户比对库命中");
        RISK_LIST.put("130001100022222", "涉诈手机号名单命中");
    }

    private static final List<String> PRIVATE_TAGS = Arrays.asList("私人银行", "专属投顾", "高净值");
    private static final List<String> GOLD_TAGS = Arrays.asList("金卡", "财富客户");

    /** 申请用户接口：建档并完成首次等级识别。 */
    public CustomerProfile apply(String name, String certNo, String mobile, String channel, Integer assetTierOverride) {
        if (name == null || name.trim().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写客户姓名");
        if (certNo == null || certNo.trim().length() < 6) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写有效证件号码");
        CustomerProfile profile = new CustomerProfile();
        profile.customerId = "CUS" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        profile.name = name.trim();
        profile.certNo = certNo.trim();
        profile.certNoMasked = mask(profile.certNo);
        profile.mobile = mobile == null ? "" : mobile.trim();
        profile.mobileMasked = mask(profile.mobile);
        profile.channel = channel == null || channel.trim().isEmpty() ? "移动端" : channel.trim();
        if (assetTierOverride != null) profile.assetTier = clampTier(assetTierOverride);
        else profile.assetTier = deriveTier(profile.certNo);
        profile.monthlyVolume = deriveVolume(profile.assetTier, profile.certNo);
        identify(profile);
        profiles.put(profile.customerId, profile);
        return profile;
    }

    public CustomerProfile get(String customerId) {
        CustomerProfile profile = customerId == null ? null : profiles.get(customerId);
        if (profile == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "客户档案不存在，请先调用申请用户接口");
        return profile;
    }

    /** 非抛异常的档案查询：流程节点取画像用于路由，档案缺失时退回技能组兜底而不中断流程。 */
    public CustomerProfile find(String customerId) {
        return customerId == null ? null : profiles.get(customerId);
    }

    /**
     * 独立等级识别接口：按证件号与资产要素判定等级，资产要素缺省时用模拟数据补齐。
     * 面向"仅识别等级、不建档"的调用场景，规则与 {@link #identify(CustomerProfile)} 完全一致。
     */
    public CustomerProfile identify(String certNo, Integer assetTier, Double monthlyVolume) {
        CustomerProfile profile = new CustomerProfile();
        profile.certNo = certNo == null ? "" : certNo.trim();
        profile.certNoMasked = mask(profile.certNo);
        profile.assetTier = assetTier == null ? deriveTier(profile.certNo) : clampTier(assetTier);
        profile.monthlyVolume = monthlyVolume == null ? deriveVolume(profile.assetTier, profile.certNo) : monthlyVolume;
        return identify(profile);
    }

    /** 等级识别子服务：按资产等级、月均交易额与风险名单判定客户等级与技能组。 */
    public CustomerProfile identify(CustomerProfile profile) {
        profile.tags.clear();
        profile.riskListed = RISK_LIST.containsKey(profile.certNo);
        if (profile.riskListed) {
            profile.tags.add("风险名单");
            profile.tags.add(RISK_LIST.get(profile.certNo));
            profile.level = "NORMAL";
            profile.levelName = "普通客户";
            profile.skillGroup = "GENERAL";
        } else if (profile.assetTier <= 1 || profile.monthlyVolume >= 5000000D) {
            profile.level = "PRIVATE";
            profile.levelName = "私人银行";
            profile.skillGroup = "PRIVATE_BANKING";
            profile.tags.addAll(PRIVATE_TAGS);
        } else if (profile.assetTier == 2 || profile.monthlyVolume >= 500000D) {
            profile.level = "GOLD";
            profile.levelName = "金卡客户";
            profile.skillGroup = "VIP";
            profile.tags.addAll(GOLD_TAGS);
        } else {
            profile.level = "NORMAL";
            profile.levelName = "普通客户";
            profile.skillGroup = "GENERAL";
            profile.tags.add("普通客户");
        }
        profile.identifiedAt = System.currentTimeMillis();
        return profile;
    }

    /** 供队列路由读取路由所需的技能组，未建档客户按普通客户处理。 */
    public String skillGroupOf(String customerId) {
        CustomerProfile profile = customerId == null ? null : profiles.get(customerId);
        return profile == null ? "GENERAL" : profile.skillGroup;
    }

    private int deriveTier(String certNo) {
        return Math.abs(certNo.hashCode()) % 3 + 1;
    }

    private double deriveVolume(int tier, String certNo) {
        int bucket = Math.abs(certNo.hashCode() / 7) % 5;
        if (tier <= 1) return 6000000D + bucket * 500000D;
        if (tier == 2) return 600000D + bucket * 200000D;
        return 20000D + bucket * 30000D;
    }

    private int clampTier(int tier) {
        return Math.max(1, Math.min(3, tier));
    }

    private String mask(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= 6) return value.charAt(0) + "****";
        return value.substring(0, 3) + "******" + value.substring(value.length() - 4);
    }
}

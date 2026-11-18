package com.example.bank.business.service;

import com.example.bank.business.config.DigitalHumanProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BankingKnowledgeService {

    @Autowired
    private DigitalHumanProperties properties;

    public List<DigitalHumanProperties.KnowledgeItem> getAllKnowledge() {
        return properties.getKnowledgeBase();
    }

    /**
     * 构建动态注入 LLM 的业务知识上下文
     */
    public String buildKnowledgePromptContext(String userQuery) {
        List<DigitalHumanProperties.KnowledgeItem> list = properties.getKnowledgeBase();
        if (list == null || list.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n【银行官方业务知识与政策规章库】：\n");
        int count = 1;
        for (DigitalHumanProperties.KnowledgeItem item : list) {
            sb.append(count++).append(". [").append(item.getCategory()).append("] ")
              .append("问：").append(item.getQuestion())
              .append(" 答：").append(item.getAnswer()).append("\n");
        }
        sb.append("（回答咨询类问题时，必须基于以上官方知识库内容准确回答，保持专业、严谨与亲切。）\n");
        return sb.toString();
    }

    /**
     * 关键词直接匹配（降级与快速响应）
     */
    public DigitalHumanProperties.KnowledgeItem matchDirectFaq(String query) {
        if (query == null || query.trim().isEmpty()) return null;
        String q = query.trim().toLowerCase();
        List<DigitalHumanProperties.KnowledgeItem> list = properties.getKnowledgeBase();
        if (list == null) return null;

        for (DigitalHumanProperties.KnowledgeItem item : list) {
            if (q.contains(item.getQuestion().toLowerCase())) {
                return item;
            }
            if (item.getKeywords() != null) {
                for (String kw : item.getKeywords()) {
                    if (q.contains(kw.toLowerCase())) {
                        return item;
                    }
                }
            }
        }
        return null;
    }
}

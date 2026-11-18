package com.example.bank.business.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "digital-human")
public class DigitalHumanProperties {

    private Llm llm = new Llm();
    private Profile profile = new Profile();
    private Rules rules = new Rules();
    private List<KnowledgeItem> knowledgeBase = new ArrayList<>();

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public Profile getProfile() {
        return profile;
    }

    public void setProfile(Profile profile) {
        this.profile = profile;
    }

    public Rules getRules() {
        return rules;
    }

    public void setRules(Rules rules) {
        this.rules = rules;
    }

    public List<KnowledgeItem> getKnowledgeBase() {
        return knowledgeBase;
    }

    public void setKnowledgeBase(List<KnowledgeItem> knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    public static class Llm {
        private String apiUrl = "https://api.commandcode.ai/provider/v1";
        private String apiKey = "user_22qW6QGMrvV3pEutiC9wKqkuN4sxJRMYU1RabxoFPxLhWiGAuR7tQepHqfcBdjuoMZyqN4SG6CcPj88nQnARcXZR";
        /**
         * 模型名必须与供应商端点匹配：本供应商标识为 {@code deepseek/deepseek-v4.1-flash}，
         * 裸名会被拒（400 unsupported_model）。
         */
        private String model = "deepseek/deepseek-v4.1-flash";
        private double temperature = 0.1;
        private int timeoutMs = 30000;

        public String getApiUrl() {
            if (apiUrl == null || apiUrl.trim().isEmpty()) {
                return "https://api.commandcode.ai/provider/v1/chat/completions";
            }
            String url = apiUrl.trim();
            if (url.endsWith("/chat/completions")) {
                return url;
            }
            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            return url + "/chat/completions";
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public static class Profile {
        private String name = "晨曦";
        private String title = "银行智能客户经理 · 晨曦";
        private String roleTag = "AI 专属顾问";
        private String avatarUrl = "/images/shuziren.png";
        private String greeting = "您好！我是您的专属金融数字顾问晨曦。请问今天有什么可以帮您？";
        private String personaPrompt = "";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getRoleTag() {
            return roleTag;
        }

        public void setRoleTag(String roleTag) {
            this.roleTag = roleTag;
        }

        public String getAvatarUrl() {
            return avatarUrl;
        }

        public void setAvatarUrl(String avatarUrl) {
            this.avatarUrl = avatarUrl;
        }

        public String getGreeting() {
            return greeting;
        }

        public void setGreeting(String greeting) {
            this.greeting = greeting;
        }

        public String getPersonaPrompt() {
            return personaPrompt;
        }

        public void setPersonaPrompt(String personaPrompt) {
            this.personaPrompt = personaPrompt;
        }
    }

    public static class Rules {
        private double videoAuthThreshold = 500000.0;
        private List<String> quickQuestions = new ArrayList<>();

        public double getVideoAuthThreshold() {
            return videoAuthThreshold;
        }

        public void setVideoAuthThreshold(double videoAuthThreshold) {
            this.videoAuthThreshold = videoAuthThreshold;
        }

        public List<String> getQuickQuestions() {
            return quickQuestions;
        }

        public void setQuickQuestions(List<String> quickQuestions) {
            this.quickQuestions = quickQuestions;
        }
    }

    public static class KnowledgeItem {
        private String category = "通用";
        private String question = "";
        private List<String> keywords = new ArrayList<>();
        private String answer = "";

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords;
        }

        public String getAnswer() {
            return answer;
        }

        public void setAnswer(String answer) {
            this.answer = answer;
        }
    }
}

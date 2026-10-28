package com.xingchen.huibiao.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 运行参数。放在 application.yml 里，换机器时不用改代码。
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String version = "0.8.2";
    private String dataDir = "data";
    private String sampleDir = "../sample_data/raw";
    private String frontendDir = "../frontend/dist";
    private boolean seedDemo = true;
    private Llm llm = new Llm();

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getSampleDir() {
        return sampleDir;
    }

    public void setSampleDir(String sampleDir) {
        this.sampleDir = sampleDir;
    }

    public String getFrontendDir() {
        return frontendDir;
    }

    public void setFrontendDir(String frontendDir) {
        this.frontendDir = frontendDir;
    }

    public boolean isSeedDemo() {
        return seedDemo;
    }

    public void setSeedDemo(boolean seedDemo) {
        this.seedDemo = seedDemo;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public static class Llm {
        private boolean enabled;
        private String baseUrl = "http://127.0.0.1:11434/v1";
        private String apiKey = "not-needed";
        private String model = "qwen2.5:14b";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
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
    }
}

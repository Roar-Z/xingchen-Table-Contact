package com.xingchen.huibiao.config;

import com.xingchen.huibiao.service.governance.TokenMeter;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

// LangChain4j 是可选的：只有把 app.llm.enabled 打开才会创建模型。
@Configuration
public class LangChain4jConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.llm.enabled", havingValue = "true")
    public ChatModel chatModel(AppProperties properties, TokenMeter tokenMeter) {
        AppProperties.Llm llm = properties.getLlm();
        log.info("启用 LangChain4j 语义解析，模型 {}，地址 {}", llm.getModel(), llm.getBaseUrl());
        return OpenAiChatModel.builder()
                .baseUrl(llm.getBaseUrl())
                .apiKey(llm.getApiKey())
                .modelName(llm.getModel())
                .temperature(0.1)
                .timeout(Duration.ofSeconds(25))
                .maxRetries(1)
                .listeners(List.of(tokenMeter))
                .build();
    }
}

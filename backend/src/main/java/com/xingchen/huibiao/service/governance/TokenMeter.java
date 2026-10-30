package com.xingchen.huibiao.service.governance;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.stereotype.Component;

// 一次智能体运行内的 token 计量。
@Component
public class TokenMeter implements ChatModelListener {

    private final ThreadLocal<long[]> current = new ThreadLocal<>();

    // 开始计量，覆盖本轮内所有模型调用（含场景识别和工具循环）。
    public void begin() {
        current.set(new long[]{0L, 0L, 0L});
    }

    // 结束计量并取出本轮用量；没有 begin 过就返回空用量。
    public Metered end() {
        long[] value = current.get();
        current.remove();
        if (value == null) {
            return new Metered(0, 0, 0);
        }
        return new Metered((int) value[0], (int) value[1], (int) value[2]);
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        long[] value = current.get();
        if (value == null || context == null || context.chatResponse() == null) {
            return;
        }
        TokenUsage usage = context.chatResponse().tokenUsage();
        value[2]++;
        if (usage == null) {
            return;
        }
        value[0] += count(usage.inputTokenCount());
        value[1] += count(usage.outputTokenCount());
    }

    private int count(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    // 一轮运行的模型用量。
    public record Metered(int inputTokens, int outputTokens, int modelCalls) {

        public int total() {
            return inputTokens + outputTokens;
        }
    }
}

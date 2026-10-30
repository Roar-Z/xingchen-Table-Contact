package com.xingchen.huibiao.service.bridge;

import java.util.List;
import java.util.Map;

// 运行现场的归档：把「这一单跑到第几步、每一步的参数」从内存搬到库里。
public interface BridgeRunArchive {

    // 存一次现场：events 已是前端直接吃的形状，meta 放阶段、耗时、模型调用次数
    void save(String id, List<Map<String, Object>> events, Map<String, Object> meta);

    // 读回这一单的事件；没有记录时返回空列表
    List<Map<String, Object>> events(String id);

    // 读回这一单的阶段/耗时等汇总信息；没有记录时返回空 Map
    Map<String, Object> meta(String id);

    // 这一单重新开跑，旧现场作废
    void clear(String id);

    // 不落库的实现：单元测试和「关掉归档」时用，行为与内存版完全一致
    BridgeRunArchive NONE = new BridgeRunArchive() {
        @Override
        public void save(String id, List<Map<String, Object>> events, Map<String, Object> meta) {
        }

        @Override
        public List<Map<String, Object>> events(String id) {
            return List.of();
        }

        @Override
        public Map<String, Object> meta(String id) {
            return Map.of();
        }

        @Override
        public void clear(String id) {
        }
    };
}

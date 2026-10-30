package com.xingchen.huibiao.service.bridge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 运行锁、取消标记、全局排队与进度事件的单测：这几件事决定了“连点两下会不会跑两遍”、
class BridgeRunTest {

    private final BridgeRun run = new BridgeRun(BridgeRunArchive.NONE);

    @Test
    void 同一单同时只能有一个运行() {
        assertTrue(run.tryStart("A"), "第一次应当抢到锁");
        assertFalse(run.tryStart("A"), "同一个 id 在跑的时候不能再抢到锁");
        assertTrue(run.tryStart("B"), "不同数据集互不影响");

        run.finish("A");
        assertTrue(run.tryStart("A"), "跑完之后可以再跑一次");
    }

    @Test
    void 取消标记只影响这一单() {
        run.tryStart("A");
        run.tryStart("B");
        run.cancel("A");
        assertTrue(run.cancelled("A"));
        assertFalse(run.cancelled("B"));

        run.finish("A");
        // 跑完再进来是新的一轮现场，取消标记不应该带过来
        assertTrue(run.tryStart("A"));
        assertFalse(run.cancelled("A"));
    }

    @Test
    void 进度事件按序号增量返回() {
        run.tryStart("A");
        run.event("A", "plan", "scan_dataset", "扫描数据集", "5 个文件");
        run.event("A", "execute", "join_tables", "跨表关联", "按工号关联 3 张表");

        Map<String, Object> all = run.snapshot("A", 0);
        assertEquals(2, ((java.util.List<?>) all.get("events")).size());
        assertTrue((Boolean) all.get("running"));

        Map<String, Object> tail = run.snapshot("A", 1);
        java.util.List<?> events = (java.util.List<?>) tail.get("events");
        assertEquals(1, events.size(), "since=1 时只返回第二条");
        assertEquals("跨表关联", ((Map<?, ?>) events.get(0)).get("label"));

        run.finish("A");
        assertFalse((Boolean) run.snapshot("A", 2).get("running"), "跑完后 running 应当为 false");
    }

    @Test
    void 没有运行现场时只报空进度不报错() {
        Map<String, Object> snapshot = run.snapshot("不存在", 0);
        assertEquals(0, ((java.util.List<?>) snapshot.get("events")).size());
        assertFalse((Boolean) snapshot.get("running"));
        assertFalse(run.cancelled("不存在"));
    }

    // 带技术细节的事件：前端要能展开看这一步的真实参数。
    @Test
    void 事件可以带上可展开的技术细节() {
        run.tryStart("A");
        run.event("A", "verify", "autonomy", "第 1 轮", "", Map.of("轮次", 1, "异常", 12));
        run.event("A", "execute", "join_tables", "跨表关联", "按工号关联", null);

        java.util.List<?> events = (java.util.List<?>) run.snapshot("A", 0).get("events");
        Map<?, ?> withData = (Map<?, ?>) events.get(0);
        Map<?, ?> without = (Map<?, ?>) events.get(1);
        assertEquals(1, (Integer) ((Map<?, ?>) withData.get("data")).get("轮次"));
        assertFalse(without.containsKey("data"), "没有细节就不该带 data 字段");
        run.finish("A");
    }

    // 全局并发：同时点单的人多于席位时必须排队，不能让所有人的单一起变慢。
    @Test
    void 超出并发上限的单会排队而不是一起跑() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            String id = "D" + i;
            pool.submit(() -> {
                try {
                    assertNull(run.begin(id), "正常排队后应当能开跑");
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    Thread.sleep(150);
                    inFlight.decrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    run.finish(id);
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "4 单都应当最终跑完");
        assertTrue(peak.get() <= BridgeRun.MAX_CONCURRENT, "同时在跑的不能超过并发上限，实际峰值 " + peak.get());
        assertTrue(peak.get() >= 2, "上限内的单应当能并行，而不是退化成串行，实际峰值 " + peak.get());
        pool.shutdown();
    }

    // 位置算错会让第三个点单的人什么提示都看不到：队列里只有等待者，
    @Test
    void 第三个单能看到排队提示而不是干等() throws Exception {
        assertNull(run.begin("A"), "第 1 单直接开跑");
        assertNull(run.begin("B"), "第 2 单占第二个席位");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<String> third = pool.submit(() -> run.begin("C"));
        Thread.sleep(600);

        Map<String, Object> snapshot = run.snapshot("C", 0);
        assertTrue((Boolean) snapshot.get("queued"), "等着席位的单要标成排队中");
        boolean told = ((List<?>) snapshot.get("events")).stream()
                .anyMatch(item -> String.valueOf(((Map<?, ?>) item).get("label")).contains("排队中"));
        assertTrue(told, "排队时必须给出“前面还有 N 单”的提示");

        run.finish("A");
        assertNull(third.get(3, TimeUnit.SECONDS), "让出席位后第 3 单应当开始执行");
        run.finish("B");
        run.finish("C");
        pool.shutdown();
    }

    // 进程重启：内存现场没了，但库里那份要能还回来。
    @Test
    void 进程重启后还能看到上一单的运行日志() {
        MemoryArchive memory = new MemoryArchive();
        String id = "D1";

        BridgeRun first = new BridgeRun(memory);
        assertNull(first.begin(id));
        first.event(id, "plan", "scan_dataset", "扫描数据集", "4 个文件", Map.of("表数", 4));
        first.event(id, "execute", "", "开始执行", "按工号关联");
        first.finish(id);

        // 新实例模拟进程重启：内存里没有任何现场，只能从归档里读
        BridgeRun restarted = new BridgeRun(memory);
        Map<String, Object> snapshot = restarted.snapshot(id, 0);
        assertEquals(2, ((List<?>) snapshot.get("events")).size(), "重启后事件应当还在");
        assertFalse((Boolean) snapshot.get("running"), "重启后这一单按已结束呈现");
        assertTrue((Boolean) snapshot.get("recovered"), "标记为从库里恢复，前端据此提示");
        assertEquals(1, ((List<?>) restarted.snapshot(id, 1).get("events")).size(), "since 增量在恢复后同样生效");
    }

    // 内存版归档：单测不拉起数据库，行为和落库版一致
    private static final class MemoryArchive implements BridgeRunArchive {
        private final Map<String, List<Map<String, Object>>> events = new ConcurrentHashMap<>();
        private final Map<String, Map<String, Object>> meta = new ConcurrentHashMap<>();

        @Override
        public void save(String id, List<Map<String, Object>> list, Map<String, Object> info) {
            events.put(id, new ArrayList<>(list));
            meta.put(id, new LinkedHashMap<>(info));
        }

        @Override
        public List<Map<String, Object>> events(String id) {
            return new ArrayList<>(events.getOrDefault(id, List.of()));
        }

        @Override
        public Map<String, Object> meta(String id) {
            return meta.getOrDefault(id, Map.of());
        }

        @Override
        public void clear(String id) {
            events.remove(id);
            meta.remove(id);
        }
    }
}

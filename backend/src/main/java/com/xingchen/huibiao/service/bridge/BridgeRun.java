package com.xingchen.huibiao.service.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 一次自主处理的过程记录：同一时刻一单只允许一个运行，过程中把每一步写成一条事件，前端按 SSE 一条条往下滚
@Service
public class BridgeRun {

    private static final Logger log = LoggerFactory.getLogger(BridgeRun.class);

    // 一条真实进度。tool 对应前端左栏的原子能力 key，没有具体工具时留空
    public static class Event {
        public int seq;
        public String at;
        public String phase;
        public String tool;
        public String label;
        public String detail;
        // 这一步可展开的技术细节（读了几张表、关联键、本轮行数、模型提示词与回答摘要…）。
        public Map<String, Object> data;
    }

    // 一单的运行现场
    private static final class Live {
        final long startedAt = System.currentTimeMillis();
        final List<Event> events = new ArrayList<>();
        int seq;
        boolean cancelled;
        boolean finished;
        // 是否占着一个全局执行席位：只有抢到席位的单才需要归还
        boolean hasSeat;
        String stage = "准备中";
        int modelCalls;
        // 模型没调通、这一单改由规则兜底：观测页的「规则兜底率」按这个算
        boolean fallback;
    }

    // 事件上限：一单最多三轮，正常不会超过几十条，这里只是兜底，防止异常循环把内存撑大
    private static final int MAX_EVENTS = 200;
    // 同时在内存里的运行现场上限，超出后先清已结束的最老记录
    private static final int MAX_RUNS = 60;
    // 单次运行的时间预算：超过就按当前结果交付，不让一次点击无限期占着线程
    private static final long MAX_RUN_MS = 180_000L;
    // 模型调用次数预算：每次规划/复核都会真的请求模型，次数兜住费用
    private static final int MAX_MODEL_CALLS = 12;
    // 跑完的现场再留一会儿：前端可能在请求返回后才补一次轮询
    private static final long KEEP_FINISHED_MS = 5 * 60 * 1000L;

    // 全局并发上限：模型调用和 Excel 解析都是重活，多人同时点单时并发 2 单最稳，多出来的排队等前面跑完
    static final int MAX_CONCURRENT = 2;
    // 排队最长等多久：等不到就明确告诉用户“稍后再试”，别把一次请求挂成十分钟
    private static final long MAX_QUEUE_WAIT_MS = 120_000L;
    // 排队期间多久回头看一次席位（同时用于响应取消）
    private static final long QUEUE_POLL_MS = 400L;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Map<String, Live> runs = new ConcurrentHashMap<>();
    private final BridgeRunArchive archive;

    // 全局席位：active 是正在跑的单数，queue 是按先来后到排的 id
    private final Object gate = new Object();
    private final Deque<String> queue = new ArrayDeque<>();
    private int active;

    public BridgeRun(BridgeRunArchive archive) {
        this.archive = archive == null ? BridgeRunArchive.NONE : archive;
    }

    // 运行锁 + 全局排队

    // 抢占运行锁：已经有一单在跑就返回 false，调用方据此拒绝重复提交
    public synchronized boolean tryStart(String id) {
        sweep();
        Live live = runs.get(id);
        if (live != null && !live.finished && !live.cancelled) return false;
        runs.put(id, new Live());
        // 新的一轮开始，上一轮的落库现场作废，否则重启后会和这次的事件混在一起
        archive.clear(id);
        return true;
    }

    // 正式开跑：先抢这一单的运行锁，再按先来后到等一个全局席位。
    public String begin(String id) {
        if (id == null || id.isBlank()) return "数据集 id 不能为空";
        if (!tryStart(id)) return "这一单正在处理，请等它跑完，或先点「取消」";

        int ahead;
        boolean mustWait;
        synchronized (gate) {
            queue.addLast(id);
            // 前面的人数 = 已经在跑的人（active）+ 排在我前面的等待者。
            ahead = queue.size() - 1 + active;
            // 空位还够、也没人排在我前面时不该说自己“在排队”：那会让用户以为要多等一会儿，
            // 结果下一秒就开始跑了，提示反而变成噪音
            mustWait = active >= MAX_CONCURRENT || queue.size() - 1 > 0;
        }
        if (mustWait) {
            // 排队也要给用户一个说法：否则前端只看到转圈，不知道是卡住了还是排在别人后面
            event(id, "plan", "", "排队中：前面还有 " + ahead + " 单在执行",
                    "同时最多 " + MAX_CONCURRENT + " 单执行，空出名额后自动开始");
        }

        long deadline = System.currentTimeMillis() + MAX_QUEUE_WAIT_MS;
        synchronized (gate) {
            try {
                while (active >= MAX_CONCURRENT || !id.equals(queue.peekFirst())) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        queue.remove(id);
                        return "排队超过 " + (MAX_QUEUE_WAIT_MS / 1000) + " 秒还没轮到，请稍后再试";
                    }
                    gate.wait(Math.min(left, QUEUE_POLL_MS));
                    if (cancelled(id)) {
                        queue.remove(id);
                        return "已按你的取消停止，这一单没有开始执行";
                    }
                }
                queue.removeFirst();
                active++;
            } catch (InterruptedException e) {
                queue.remove(id);
                Thread.currentThread().interrupt();
                return "排队被中断，请重新提交";
            }
        }

        Live live = runs.get(id);
        if (live != null) live.hasSeat = true;
        if (mustWait) {
            event(id, "plan", "", "轮到这一单，开始处理", "前面 " + ahead + " 单已让出名额");
        }
        return null;
    }

    public void finish(String id) {
        Live live = runs.get(id);
        boolean release;
        if (live != null) {
            release = live.hasSeat;
            live.hasSeat = false;
            live.finished = true;
        } else {
            release = false;
        }
        synchronized (gate) {
            // 异常退出时这一单可能还挂在队列里，不清掉会把后面的人一直堵住
            queue.remove(id);
            if (release) {
                active--;
                if (active < 0) active = 0;
            }
            gate.notifyAll();
        }
        // 收尾再落一次库：把 finished、总耗时、最终阶段写进去，重启后看到的是“已结束”而不是“进行中”
        persist(id);
    }

    // 事件

    // 写一条进度；没有运行现场（比如单独调用“只规划”接口）时什么都不记
    public void event(String id, String phase, String tool, String label, String detail) {
        event(id, phase, tool, label, detail, null);
    }

    // 写一条带技术细节的进度：data 是这一步的可展开内容（行数、关联键、模型提示词…）。
    public void event(String id, String phase, String tool, String label, String detail,
                      Map<String, Object> data) {
        if (id == null || label == null || label.isBlank()) return;
        Live live = runs.get(id);
        if (live == null) return;
        Event event = new Event();
        synchronized (live) {
            if (live.events.size() >= MAX_EVENTS) return;
            event.seq = ++live.seq;
            event.at = clock();
            event.phase = phase;
            event.tool = tool;
            event.label = label;
            event.detail = detail == null ? "" : detail;
            event.data = data;
            live.events.add(event);
            live.stage = label;
        }
        persist(id);
    }

    public void cancel(String id) {
        Live live = runs.get(id);
        if (live == null) return;
        live.cancelled = true;
    }

    // 是否已被取消：执行/循环的检查点会看这个标记，尽力在下一步之前停下
    public boolean cancelled(String id) {
        Live live = runs.get(id);
        return live != null && live.cancelled;
    }

    // 预算用完的原因，没用完返回 null
    public String budgetNote(String id) {
        Live live = runs.get(id);
        if (live == null) return null;
        long elapsed = System.currentTimeMillis() - live.startedAt;
        if (elapsed > MAX_RUN_MS) return "已跑 " + (elapsed / 1000) + " 秒，超过 " + (MAX_RUN_MS / 1000) + " 秒预算";
        if (live.modelCalls >= MAX_MODEL_CALLS) return "大模型调用已达 " + MAX_MODEL_CALLS + " 次上限";
        return null;
    }

    // 记一次真实的大模型请求，供预算和观测使用
    public void noteModelCall(String id) {
        Live live = runs.get(id);
        if (live == null) return;
        live.modelCalls++;
    }

    // 模型调用没成功、这一单退回规则处理：记下来，运行观测里要能看出“兜底过”
    public void noteModelFailure(String id) {
        Live live = runs.get(id);
        if (live == null) return;
        live.fallback = true;
    }

    // 这一单真实发起了几次模型请求（含失败的），写运行观测时用
    public int modelCalls(String id) {
        Live live = runs.get(id);
        return live == null ? 0 : live.modelCalls;
    }

    // 这一单是否发生过“模型失败 → 规则兜底”
    public boolean fallback(String id) {
        Live live = runs.get(id);
        return live != null && live.fallback;
    }

    // 轮询

    // 前端轮询用：since 之后的新事件 + 当前阶段/是否在跑/是否已取消
    public Map<String, Object> snapshot(String id, int since) {
        Live live = runs.get(id);
        Map<String, Object> out = new LinkedHashMap<>();
        if (live == null) {
            // 内存里没有这一单：可能进程重启过。库里还留着上一次的现场，就按“已结束”还回去，
            // 这样用户刷新页面仍能看到它当时跑到哪，而不是一片空白。
            List<Map<String, Object>> stored = archive.events(id);
            if (stored.isEmpty()) {
                out.put("running", false);
                out.put("finished", false);
                out.put("queued", false);
                out.put("cancelled", false);
                out.put("stage", "");
                out.put("seq", 0);
                out.put("model_calls", 0);
                out.put("elapsed_ms", 0);
                out.put("budget_ms", MAX_RUN_MS);
                out.put("events", List.of());
                return out;
            }
            Map<String, Object> meta = archive.meta(id);
            out.put("running", false);
            out.put("finished", true);
            out.put("queued", false);
            out.put("cancelled", false);
            out.put("recovered", true);
            out.put("stage", meta.getOrDefault("stage", ""));
            out.put("seq", meta.getOrDefault("seq", 0));
            out.put("model_calls", meta.getOrDefault("model_calls", 0));
            out.put("elapsed_ms", meta.getOrDefault("elapsed_ms", 0));
            out.put("budget_ms", MAX_RUN_MS);
            List<Map<String, Object>> tail = new ArrayList<>();
            for (Map<String, Object> item : stored) {
                Object seq = item.get("seq");
                if (seq instanceof Integer value && value > since) tail.add(item);
            }
            out.put("events", tail);
            return out;
        }
        out.put("running", !live.finished);
        out.put("finished", live.finished);
        // 已经进了运行现场、但还没抢到执行席位：前端据此显示“排队中”，
        // 而不是让用户以为它已经在跑
        out.put("queued", !live.hasSeat && !live.finished);
        out.put("cancelled", live.cancelled);
        out.put("stage", live.stage);
        out.put("seq", live.seq);
        out.put("model_calls", live.modelCalls);
        out.put("elapsed_ms", System.currentTimeMillis() - live.startedAt);
        out.put("budget_ms", MAX_RUN_MS);
        List<Event> list = new ArrayList<>();
        synchronized (live) {
            for (Event event : live.events) {
                if (event.seq > since) list.add(event);
            }
        }
        out.put("events", shape(list));
        return out;
    }

    // 落库

    // 把当前现场写一份到库里：事件条数很少，整份覆盖比增量追加更简单、也不会写重
    private void persist(String id) {
        Live live = runs.get(id);
        if (live == null) return;
        List<Event> copy;
        Map<String, Object> meta = new LinkedHashMap<>();
        synchronized (live) {
            copy = new ArrayList<>(live.events);
            meta.put("stage", live.stage == null ? "" : live.stage);
            meta.put("seq", live.seq);
            meta.put("model_calls", live.modelCalls);
            meta.put("finished", live.finished);
            meta.put("elapsed_ms", System.currentTimeMillis() - live.startedAt);
        }
        try {
            archive.save(id, shape(copy), meta);
        } catch (Exception e) {
            // 归档是辅助能力，写失败只留痕，绝不影响正在跑的这一单
            log.warn("运行现场归档失败：{}", e.getMessage());
        }
    }

    // 事件 → 前端直接吃的形状（字段名小写下划线，前端不再二次映射）
    private static List<Map<String, Object>> shape(List<Event> events) {
        List<Map<String, Object>> shaped = new ArrayList<>();
        for (Event event : events) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", event.seq);
            item.put("at", event.at);
            item.put("phase", event.phase);
            item.put("tool", event.tool == null ? "" : event.tool);
            item.put("label", event.label);
            item.put("detail", event.detail);
            if (event.data != null && !event.data.isEmpty()) item.put("data", event.data);
            shaped.add(item);
        }
        return shaped;
    }

    // 清理跑完很久的现场，避免长跑的服务内存只增不减
    private void sweep() {
        if (runs.size() < MAX_RUNS) return;
        long now = System.currentTimeMillis();
        runs.entrySet().removeIf(entry -> {
            Live live = entry.getValue();
            return live.finished && now - live.startedAt > KEEP_FINISHED_MS;
        });
        if (runs.size() < MAX_RUNS) return;
        runs.entrySet().stream()
                .filter(entry -> entry.getValue().finished)
                .min(Comparator.comparingLong(entry -> entry.getValue().startedAt))
                .map(Map.Entry::getKey)
                .ifPresent(runs::remove);
    }

    // 事件时间戳只用于展示，不参与计算
    private static String clock() {
        return LocalTime.now().format(CLOCK);
    }
}

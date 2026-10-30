package com.xingchen.huibiao.service;

import com.xingchen.huibiao.dto.ApiRequests.FileRef;
import com.xingchen.huibiao.dto.ApiRequests.TaskConfig;
import com.xingchen.huibiao.model.IntentResult;
import com.xingchen.huibiao.model.ProcessResult;
import com.xingchen.huibiao.model.TemplateItem;
import com.xingchen.huibiao.util.TextUtils;
import org.springframework.stereotype.Service;

import java.util.List;

// 场景路由。解析结果和用户手动配置冲突时，以用户选的场景为准，
@Service
public class SceneRouter {

    private final LeaveSettlementService leaveSettlement;
    private final OvertimeService overtimeService;
    private final SettingsService settings;

    public SceneRouter(LeaveSettlementService leaveSettlement, OvertimeService overtimeService,
                       SettingsService settings) {
        this.leaveSettlement = leaveSettlement;
        this.overtimeService = overtimeService;
        this.settings = settings;
    }

    public IntentResult align(IntentResult plan, TaskConfig config) {
        String scene = config == null ? null : config.scene();
        if (scene != null && !scene.isBlank() && !scene.equals(plan.scene)) {
            plan.scene = scene;
            plan.sceneName = "leave".equals(scene) ? "假期余量结算" : "加班记录桥接";
            TemplateItem template = settings.builtIn("leave".equals(scene) ? "tpl_absence" : "tpl_overtime");
            // 上传的模板是用户明确指定的，换场景时也保留它的列
            if (template != null && (plan.targetTemplate == null || plan.targetTemplate.builtIn())) {
                plan.targetTemplate = template;
            }
            plan.joinKeys = "leave".equals(scene) ? List.of("工号") : List.of();
            plan.calculation = "leave".equals(scene)
                    ? "按工号关联年假余额，先扣年假，超出部分转事假；天数 × 8 小时"
                    : "按部门和日期筛选，申请加班转为数值后输出";
        }
        if (plan.targetTemplate == null) {
            TemplateItem template = settings.builtIn("leave".equals(plan.scene) ? "tpl_absence" : "tpl_overtime");
            if (template == null) {
                throw new IllegalStateException("内置输出模板丢失，请检查数据库里的设置（data/huibiao.db）");
            }
            plan.targetTemplate = template;
        }
        return plan;
    }

    public ProcessResult process(List<FileRef> refs, IntentResult plan, TaskConfig config) {
        IntentResult aligned = align(plan, config);
        if (TextUtils.isBlank(aligned.scene)) {
            throw new IllegalArgumentException("没有识别出任务场景");
        }
        if ("leave".equals(aligned.scene)) {
            return leaveSettlement.settle(refs, aligned, config);
        }
        return overtimeService.process(refs, aligned, config);
    }
}

<script setup>
import { onBeforeUnmount } from 'vue'
import Icon from '../components/Icon.vue'
import { project } from '../project'
import { clearTopbar, useTopbar } from '../topbar'

// 纯前端静态页：不读接口，断网也能看，所以内容全部写死在组件里
useTopbar({
  title: '项目简介',
  desc: '一句话说明这个项目为谁解决什么问题、怎么处理表格，以及结果为什么敢拿来用。'
})

onBeforeUnmount(() => clearTopbar())

const facts = [
  { label: '两条处理路径', value: '标准模式 / 智能体模式' },
  { label: '标准字段', value: '20 个' },
  { label: '异常检查', value: '6 类' }
]

const pains = [
  {
    no: '01',
    title: '同一个人，四张表',
    text: 'HR 花名册、ERP 薪资、OA 请假、OA 加班各自导出一份，工号叫「员工编号」或「人员代码」，部门叫「组织」或「班组」，口径对不上就没法合并。'
  },
  {
    no: '02',
    title: '手工拼表，错一处全错',
    text: 'VLOOKUP 一拉到底，只要有一个工号对不上，整列就错位；找不到的行被直接丢掉，没人知道少了谁。'
  },
  {
    no: '03',
    title: '目标系统模板固定',
    text: '导入 HR 系统的表列顺序、列名、日期格式都写死，人工搬运容易漏列、把日期当文本、把金额导成文本。'
  }
]

const modes = [
  {
    icon: 'table',
    name: '标准模式 · 表格智作',
    for: '已经有目标模板，只差把源表填进去',
    steps: ['上传源表与目标模板', '生成字段映射并逐列核对', '按模板导出 xlsx / xls / CSV'],
    note: '适合每月固定上报的报表，映射确认一次，之后照着跑。'
  },
  {
    icon: 'spark',
    name: '智能体模式 · 跨系统桥接',
    for: '只有一堆杂乱导出，先说业务目标',
    steps: ['上传整个文件夹或 zip', '识别表类型、对齐字段、按工号合并', '隔离异常并交付标准表'],
    note: '交付结果自动记进任务记录，并归档到我的仓库，不用再去下载目录里翻。'
  }
]

const abilities = [
  {
    icon: 'columns',
    title: '表类型识别',
    text: '按文件名与表头判断这是 HR 员工表、ERP 薪资表、OA 请假单、OA 加班表还是 HR 年假表，同名重复导出的表只算一次。'
  },
  {
    icon: 'sliders',
    title: '字段语义对齐',
    text: '把各系统不同的叫法收拢到工号、姓名、部门、应发工资、实发工资、加班小时等 20 个标准字段，识别不了的列不会硬塞。'
  },
  {
    icon: 'link',
    title: '跨表合并',
    text: '按工号优先、姓名兜底关联多张表，一人一行；一条加班或请假记录能挂到具体的人，而不是合并成一团。'
  },
  {
    icon: 'shield',
    title: '异常隔离',
    text: '关联不上、必填为空、日期格式不对、数值越界、乱码、重复行统一进异常清单，原值摘要一并带上，方便回原表核对。'
  },
  {
    icon: 'download',
    title: '模板化交付',
    text: '一次产出标准表（.xlsx）、HR 导入表（.xls）和异常清单（.xlsx），列顺序按目标系统要求排好。'
  },
  {
    icon: 'spark',
    title: '经验记忆',
    text: '同一类数据集确认过的关联键、口径与筛选条件会记下来，下次遇到同类表先复用，再让人确认，不用每次从头教。'
  }
]

const quality = [
  { title: '行数对账', text: '源表行数、输出行数、异常行数三个数字一起给出，少了多少一眼能看出来。' },
  { title: '待复核标记', text: '只要异常行大于 0，任务状态就是「待复核」，不会把有问题的表当成完成品。' },
  { title: '运行观测', text: '每单都记次数、成功率、规则兜底率、token 消耗和平均耗时，数字能在页面上核对。' },
  { title: '审计留痕', text: '规划、映射、导出、清理这些关键动作都落库，出了问题能倒查是哪一步改了什么。' }
]

const stack = [
  { name: '前端', text: 'Vue 3 + Vite，页面与接口解耦，表格预览分页加载' },
  { name: '后端', text: 'Spring Boot 3.4.5 / Java 21，Apache POI 读写 Excel' },
  { name: '数据', text: 'SQLite 单文件 + 本地目录（表格、产出、数据集各自归档）' },
  { name: '模型', text: 'LangChain4j 可接云端或本地模型；超时或未启用时自动退回规则链，功能不中断' }
]
</script>

<template>
  <div class="page about">
    <section class="hero">
      <span class="hero-eyebrow">竞赛项目 · {{ project.team }}</span>
      <h1 class="hero-title">让跨系统的表格，在同一张表里用同一套口径</h1>
      <p class="hero-desc">
        面向制造企业常见的 HR / ERP / OA 多系统导出表：把「多份来源表 → 一张可导入的标准表」这件事交给智能体做，
        识别表类型、对齐字段、按工号合并、隔离异常，最后按目标系统模板导出。每一步都能回看，异常不会被悄悄丢掉。
      </p>
      <div class="hero-facts">
        <div v-for="fact in facts" :key="fact.label" class="hero-fact">
          <b>{{ fact.value }}</b>
          <span>{{ fact.label }}</span>
        </div>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>为什么需要它</h2>
        <span class="panel-sub">多系统之间最常见的三个卡点</span>
      </div>
      <div class="pain-list">
        <article v-for="pain in pains" :key="pain.no" class="pain">
          <span class="pain-no">{{ pain.no }}</span>
          <div>
            <h3>{{ pain.title }}</h3>
            <p>{{ pain.text }}</p>
          </div>
        </article>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>两条处理路径</h2>
        <span class="panel-sub">按手上有什么来决定从哪进去</span>
      </div>
      <div class="mode-grid">
        <article v-for="mode in modes" :key="mode.name" class="mode">
          <div class="mode-head">
            <span class="mode-icon"><Icon :name="mode.icon" :size="16" /></span>
            <div>
              <h3>{{ mode.name }}</h3>
              <p class="mode-for">{{ mode.for }}</p>
            </div>
          </div>
          <ol class="mode-steps">
            <li v-for="(step, index) in mode.steps" :key="step">
              <span>{{ index + 1 }}</span>
              {{ step }}
            </li>
          </ol>
          <p class="mode-note">{{ mode.note }}</p>
        </article>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>核心能力</h2>
        <span class="panel-sub">真正的活儿都在这一步一步里</span>
      </div>
      <div class="ability-grid">
        <article v-for="item in abilities" :key="item.title" class="ability">
          <span class="ability-icon"><Icon :name="item.icon" :size="15" /></span>
          <h3>{{ item.title }}</h3>
          <p>{{ item.text }}</p>
        </article>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>结果为什么敢用</h2>
        <span class="panel-sub">不只看「跑完了」，还要能核对</span>
      </div>
      <div class="quality-grid">
        <article v-for="item in quality" :key="item.title" class="quality">
          <h3><Icon name="check" :size="13" />{{ item.title }}</h3>
          <p>{{ item.text }}</p>
        </article>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>技术架构</h2>
        <span class="panel-sub">单机可跑，不依赖外部监控</span>
      </div>
      <div class="stack-grid">
        <div v-for="item in stack" :key="item.name" class="stack-item">
          <span class="stack-name">{{ item.name }}</span>
          <p>{{ item.text }}</p>
        </div>
      </div>
    </section>

    <footer class="about-foot">
      <span>{{ project.team }} · v{{ project.version }}</span>
    </footer>
  </div>
</template>

<style scoped>
/* 版面沿用全站的浅灰底 + 白面板，不加渐变和大色块，避免看起来像宣传页 */
.about {
  padding-bottom: 8px;
}

.hero {
  padding: 28px 30px 26px;
  margin-bottom: 16px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 8px;
}

.hero-eyebrow {
  display: inline-block;
  padding: 3px 8px;
  color: var(--accent);
  font-size: 11px;
  background: var(--accent-soft);
  border-radius: 4px;
}

.hero-title {
  max-width: 760px;
  margin: 14px 0 10px;
  font-size: 24px;
  font-weight: 600;
  line-height: 1.45;
  letter-spacing: 0.2px;
}

.hero-desc {
  max-width: 860px;
  margin: 0;
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.85;
}

.hero-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 36px;
  padding-top: 20px;
  margin-top: 22px;
  border-top: 1px solid var(--line-soft);
}

.hero-fact {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.hero-fact b {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}

.hero-fact span {
  color: var(--muted);
  font-size: 12px;
}

.pain-list {
  padding: 4px 22px 18px;
}

.pain {
  display: flex;
  gap: 16px;
  padding: 16px 0;
  border-bottom: 1px solid var(--line-soft);
}

.pain:last-child {
  border-bottom: none;
  padding-bottom: 4px;
}

.pain-no {
  flex: none;
  padding-top: 1px;
  color: var(--muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.pain h3 {
  margin: 0 0 4px;
  font-size: 14px;
  font-weight: 600;
}

.pain p {
  max-width: 900px;
  margin: 0;
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.75;
}

.mode-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  padding: 18px;
}

.mode {
  padding: 18px;
  background: #fbfcfd;
  border: 1px solid var(--line-soft);
  border-radius: 8px;
}

.mode-head {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.mode-icon,
.ability-icon {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  color: var(--accent);
  background: var(--accent-soft);
  border-radius: 6px;
}

.mode h3 {
  margin: 1px 0 3px;
  font-size: 14px;
  font-weight: 600;
}

.mode-for {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
}

.mode-steps {
  margin: 16px 0 0;
  padding: 0;
  list-style: none;
}

.mode-steps li {
  display: flex;
  gap: 9px;
  align-items: baseline;
  padding: 6px 0;
  color: var(--ink-2);
  font-size: 13px;
}

.mode-steps li + li {
  border-top: 1px dashed var(--line-soft);
}

.mode-steps span {
  flex: none;
  width: 16px;
  color: var(--muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.mode-note {
  margin: 14px 0 0;
  padding-top: 12px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
  border-top: 1px solid var(--line-soft);
}

.ability-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  padding: 1px;
  background: var(--line-soft);
}

.ability {
  padding: 18px;
  background: var(--panel);
}

.ability h3 {
  margin: 10px 0 6px;
  font-size: 13px;
  font-weight: 600;
}

.ability p {
  margin: 0;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.75;
}

.quality-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  padding: 18px;
}

.quality {
  padding: 14px 16px;
  border: 1px solid var(--line-soft);
  border-radius: 6px;
}

.quality h3 {
  display: flex;
  gap: 6px;
  align-items: center;
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ok);
}

.quality p {
  margin: 0;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.75;
}

.stack-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  padding: 18px;
}

.stack-item {
  padding-left: 12px;
  border-left: 2px solid var(--line);
}

.stack-name {
  display: block;
  margin-bottom: 5px;
  font-size: 12px;
  font-weight: 600;
}

.stack-item p {
  margin: 0;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.7;
}

.about-foot {
  display: flex;
  justify-content: flex-start;
  padding: 4px 2px 0;
  color: var(--muted);
  font-size: 12px;
}

@media (max-width: 960px) {
  .ability-grid,
  .stack-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .mode-grid,
  .quality-grid,
  .ability-grid,
  .stack-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .hero-facts {
    gap: 22px;
  }

  .about-foot {
    flex-direction: column;
    gap: 4px;
  }
}
</style>

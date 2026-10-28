# 汇表（xingchen-huibiao）

一个把 Excel 表格转换成 HR 系统能直接导入的格式的小项目。前端 Vue 3，后端 Spring Boot，字段匹配部分接了大模型（通义千问 / DeepSeek / 本地 Ollama 都行）。

主要解决的事：OA 导出的请假单、ERP 导出的加班表，和 HR 系统要求的导入模板，表头、日期格式、工时算法都不一样，手动复制粘贴很慢还容易错。这个项目把这一步做成流程：读表 → 识别要做什么 → 生成字段映射 → 用户确认 → 执行 → 导出。

## 现在能做什么

- 读 `.xls` / `.xlsx` / `.csv`，自动找表头行、统计行数和样例值
- 用一句话说目标（"请假单转 HR 缺勤，时间范围 4 月 1 日到 4 月 30 日"），后端识别场景和日期范围
- 生成字段映射：先按规则算一遍，再让大模型复核补充；日期和时刻分成两列时拼成 `2026-04-27 20:00` 这种格式
- 校验必填列和表达式，补不上的列留给用户自己处理，不瞎猜
- 映射方案要用户点确认才会执行
- 执行时用 Java 做筛选、年假扣减、工时换算、脏数据隔离，导出 `.xls` 和 `.xlsx`
- 结果页可以直接用一句话改结果，改完重算
- 每一步的工具调用和修正都留了记录，能在页面里回看
- 智能体模式：把整个数据集（zip 或一整个文件夹）丢进来，只说业务目标，它自己找表、把不同系统的同义字段对齐（工号 ↔ 员工编号）、跨表 join 计算、隔离异常行，最后导出标准表和异常清单；字段对齐改一次会记成规则，下次同类报表直接复用

## 智能体模式怎么用

新建任务页最上面切到「智能体模式」，然后：

1. 拖一个 zip，或者直接选整个导出文件夹；
2. 写一句业务目标，比如「把 HR 入职表和 ERP 薪资表做桥接，计算每个员工应发工资，过滤掉入职信息缺失的异常人员，输出符合 OA 导入标准的表格」；
3. 看它生成的方案（哪张是主表、按哪个键关联、对齐了哪些字段、怎么算、异常怎么判），确认后执行；
4. 下载 `标准表.xlsx`、`标准表.xls`（老系统只认 xls）和 `异常清单.xlsx`。

几个处理过的坑：

- 不同系统导出的同一批数据（ERP 导出的加班表和导入 HR 的加班表），表头、行数、首尾行一样就只留一份，避免算两遍；
- 同类表有多张时，每类只用字段最全的那张，另一张标「备选表」；
- 没记录的加班/请假算 0，不算异常；只有主数据（员工、薪资、年假）缺行才判成信息缺失，不然异常清单会被无关的表塞满；
- 关联表里出现主表没有的工号，会单独列进异常清单；
- 日期统一成 `yyyy-MM-dd` 再导出。

状态和记忆都存本地 JSON：数据集在 `backend/data/bridge/datasets/{id}/meta.json`（表格元数据、规划方案、执行结果），自定义字段规则在 `backend/data/bridge/rules.json`。

不配大模型也能跑，只是字段匹配用纯规则。

## 技术栈

- 后端：Java 21、Spring Boot 3.4、Apache POI
- 大模型：LangChain4j（AiServices + `@Tool`），走 OpenAI 兼容接口
- 前端：Vue 3、Vite 6、Element Plus、vue-router
- 数据都存本地文件，不需要数据库

## 目录

```
backend/
  pom.xml
  start.cmd
  config/application.example.yml   # 大模型配置模板
  src/main/java/com/xingchen/huibiao/
    config/ dto/ model/ util/
    service/     # 读表、意图、映射、执行、导出、智能体
    web/         # 接口
  src/main/resources/application.yml

frontend/
  src/           # 页面和组件
  package.json  vite.config.js  start.cmd
```

## 怎么跑

后端（默认 8000 端口）：

```bash
cd backend
mvn -DskipTests package
java -jar target/huibiao-backend-0.8.2.jar
```

前端（默认 5173，`/api` 已经代理到 8000）：

```bash
cd frontend
npm install
npm run dev
```

Windows 下也可以直接双击各自的 `start.cmd`。

## 配大模型（可选）

不配的话 `app.llm.enabled=false`，走纯规则。要用 AI 就设环境变量：

```powershell
$env:HUBIAO_LLM_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:HUBIAO_LLM_MODEL    = "qwen-plus"
$env:HUBIAO_LLM_API_KEY  = "<改成自己的密钥>"
$env:HUBIAO_LLM_ENABLED  = "true"
```

或者把 `backend/config/application.example.yml` 复制成 `backend/config/application.yml` 再填。启动日志会打印当前用的模型和地址，也能在 `/api/health` 里看到。

## 说明

- 仓库里没有密钥，也没有业务数据。密钥走环境变量或本机 `backend/config/application.yml`（已 gitignore），上传的表、任务记录、导出结果都在 `backend/data/`（同样没提交）。
- 原始上传的表只读，处理的时候另开工作簿写结果，不会改源文件。
- 演示用的样例表在 `sample_data/`，没放进仓库；目录为空时后端会跳过演示数据初始化。

关键词：Excel 转换、字段映射、表格清洗、HR 导入模板、请假单、加班表、AI Agent、LangChain4j、Spring Boot、Vue 3

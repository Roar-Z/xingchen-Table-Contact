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

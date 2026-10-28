# 汇表 · Xingchen Huibiao

**AI 表格智能体 / Excel data-bridge agent**：把 OA、ERP 导出的杂乱表格，按 HR（或任意业务系统）的导入模板自动清洗、字段映射、校验并导出，全程可审计、可人工确认。

> Keywords: Excel 表格转换 · 字段映射 · 数据清洗 · 智能体 Agent · LLM · LangChain4j · Spring Boot · Vue 3 · HR 导入模板 · 加班表 / 请假单 · ETL · 数据治理 · 审计日志

## 它解决什么问题

企业内部系统各管一段：OA 里的请假单、ERP 里的加班记录、HR 系统要求的导入模板，三者的表头、日期格式、工时口径都不一样，靠人工复制粘贴既慢又容易出错，还说不清改了什么。

这个项目把这段"跨系统搬表"的活儿交给智能体：

1. **读表**：识别表头位置、列含义、样例值，兼容 `.xls` / `.xlsx` / `.csv`。
2. **理解目标**：从一句话目标里识别场景（请假单转 HR 缺勤、加班表汇总等）、时间范围和业务口径。
3. **规划映射**：规则先给出全量映射，大模型逐条复核补充；日期 + 时刻拆成两列时自动拼成标准日期时间（`2026-04-27 20:00`）。
4. **校验与兜底**：必填列、表达式合法性、列名白名单校验；补不齐的列交给用户，不瞎猜。
5. **人工确认**：方案必须由用户确认后才执行，智能体不会自动放行。
6. **执行与交付**：确定性 Java 代码完成筛选、年假扣减、工时换算、异常隔离，输出 `.xls` + `.xlsx` 双格式，附异常日志。

### 核心特性

- **确定性优先**：数字、日期、工时、年假扣减全部由 Java 计算，大模型只做语义判断，不碰数值。
- **规则兜底可用**：不配置大模型也能完整跑通（纯规则匹配），配置后由 AI 提升字段匹配质量。
- **重复上传幂等**：按 SHA-256 内容指纹判定，同内容重复上传会覆盖旧记录并重新解析，不新增冗余文件。
- **脏数据隔离**：必填缺失、工号对不上的行单独进异常日志，不影响正常行导出。
- **全程留痕**：智能体每一步工具调用、自动修正、待确认项都记录在轨迹里，可回看、可审计。
- **经验复用**：跑过的映射方案会沉淀为记忆，同场景下次优先复用。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.4 · Apache POI 5.5 |
| 智能体 | LangChain4j 1.0（AiServices + `@Tool`），OpenAI 兼容接口（通义千问 / DeepSeek / 本地 Ollama） |
| 前端 | Vue 3 · Vite 6 · Element Plus · vue-router |
| 数据 | 本地文件存储（上传表、任务快照、导出结果），无需数据库 |

## 目录结构

```
backend/
  pom.xml
  start.cmd                     # Windows 一键构建并启动
  config/application.example.yml # 大模型配置模板（复制为 application.yml 后填密钥）
  src/main/java/com/xingchen/huibiao/
    config/   dto/   model/   util/
    service/                      # 读表、意图解析、映射、执行、导出、智能体
    web/                          # REST 接口
  src/main/resources/application.yml

frontend/
  package.json  vite.config.js  start.cmd
  src/                            # 页面、组件、接口封装、设计系统
```

> 演示用样例表、内部设计文档、运行时数据都不在仓库内。示例目录为空时后端会自动跳过演示数据初始化。

## 快速开始

**后端（默认 http://127.0.0.1:8000）**

```bash
cd backend
mvn -DskipTests package
java -jar target/huibiao-backend-0.8.2.jar
# 或直接双击 backend/start.cmd
```

**前端（默认 http://127.0.0.1:5173，已代理 /api 到 8000）**

```bash
cd frontend
npm install
npm run dev
# 或直接双击 frontend/start.cmd
```

## 大模型配置（可选）

不配置时 `app.llm.enabled=false`，使用纯规则匹配。启用 AI：

```powershell
$env:HUBIAO_LLM_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:HUBIAO_LLM_MODEL    = "qwen-plus"
$env:HUBIAO_LLM_API_KEY  = "<你的密钥>"
$env:HUBIAO_LLM_ENABLED  = "true"
```

或把 `backend/config/application.example.yml` 复制为 `backend/config/application.yml` 后填写。启动日志会打印「启用 LangChain4j 语义解析，模型 xxx，地址 xxx」，也可访问 `/api/health` 查看 `llm_enabled`。

## 安全说明

- 仓库内**不包含任何真实密钥**：密钥只通过环境变量或本机 `backend/config/application.yml` 提供，该文件已被 `.gitignore` 排除。
- 仓库内**不包含任何业务数据**：上传表、任务记录、导出结果都在本机 `backend/data/`，同样不入库。
- 原始上传表只读不改，处理时另开工作簿写入结果。

## 接口一览

`POST /api/files/upload` 上传源表 / 模板 · `POST /api/agent/plan` 智能体规划方案 · `POST /api/tasks` 创建并异步执行 · `GET /api/tasks/{id}` 查询进度 · `GET /api/tasks/{id}/rows` 结果分页 · `GET /api/tasks/{id}/download/{artifact}` 下载导出文件

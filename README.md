# 汇表 · 跨系统表格数据自动化桥接与处理智能体

Xingchen Huibiao · Cross-system Spreadsheet Bridge Agent  
星尘汇表智联 · 2026 东莞百景大赛参赛项目

汇表是一个部署在企业内网的小工具：把 ERP、OA、HR 导出的 Excel 表格读进来，按业务规则完成跨表关联、年假抵扣、加班筛选，最后输出 HR 老系统可以直接导入的 `.xls` 文件，并附一份异常日志。

它不改造老系统，也不要求厂商开放接口。业务人员只需要把表拖进来，用一句话说明要做什么。

## 这个项目解决什么问题

制造企业里常见的场景：

- ERP 导出加班表，日期是 `4/1/26`，申请加班可能为空或不是数字；
- OA 导出请假单，表头叫“申请人”“请假类型”，和 HR 的叫法不一样；
- 年假表第一行是标题，真正的表头在第二行；
- HR 系统只认固定 8 列的 `.xls` 模板，多一列都会导入失败。

人工做法是 VLOOKUP、筛选、复制、拼模板，每个月重复一遍。汇表把这一段自动化，并把有问题的行单独留下来，不让一条脏数据毁掉整批结果。

## 已实现的功能

- 上传 `.xlsx` / `.xls` / `.csv`，自动识别工作表、表头行、文件角色；
- 用自然语言描述任务，识别场景、部门、日期区间、关联键；
- 字段别名匹配，支持在映射步骤手动指定来源列；
- 假期余量结算：年假优先抵扣，超出转事假，年假缺失按 0 处理并记异常；
- 加班记录筛选：按部门、日期过滤，校验加班时长；
- 异常行隔离，生成独立异常日志，异常占比超阈值标记待复核；
- 异步任务、进度轮询、任务列表、详情、下载；
- 结果表分页浏览：每页 10 / 20 / 50 / 100 行，支持首页/末页/跳页，表头吸顶、← → 翻页、Ctrl+F 聚焦搜索；
- 结果表模糊查询：全列或指定列搜索，空格分隔多个关键字同时命中，命中内容高亮；
- 处理成功的结果表支持一句话修改：改值、加减、删行、排序、增删列，改完自动重算并重新导出；
- 表格智作：拖拽或点选上传一个或多个 Excel、一句话新建、多表融合，每张表独立分页查询与双击编辑；
- 我的仓库：顶部右侧进入，按名称/列名搜索并打开、下载、重命名、删除，附带全量操作日志；
- 离线接口文档 `/docs/`；
- 可选 LangChain4j 语义增强，不配置模型也能完整运行。

## 智能体模式（核心）

新建任务页最上面有「标准模式 / 智能体模式」两档，默认是标准模式。切到智能体模式以后，交互只剩下三步：

1. 把一个压缩包（或整个文件夹里的表格）丢进来；
2. 用一句业务目标说清这单要做什么，不用写文件名，也不用写字段名；
3. 看方案、点确认，然后下载标准表和异常清单。

页面里做的事，就是智能体干活的顺序：

| 步骤 | 原子能力 | 说明 |
| --- | --- | --- |
| 扫描数据集 | 文件检索 | 遍历目录，逐张表读出 sheet、表头、行数和样例值，按文件名和表头判断它是 HR 员工表、ERP 薪资表、OA 请假单还是加班表 |
| 解析表格 | 表格解析 | 自动找表头行，`4/1/26`、`2026年4月1日` 这几种日期写法都能认 |
| 字段语义对齐 | 语义对齐 | HR 的「员工编号」和 ERP 的「工号」、「姓名」和「职员名称」、「天」「合计小时」这类简写，都对齐到同一套标准字段 |
| 跨表关联 | 关联 + 计算 | 按工号左连接，同一人多条加班/请假记录按人合成一行，数值列累加，再按业务口径算应发工资、合计加班小时等 |
| 异常隔离 | 异常检测 | 缺关联键、关联表里对不上、入职信息缺失、日期无法识别、数值不是数字、疑似乱码，命中就进异常清单，不影响正常行 |
| 标准化导出 | 导出 | 按目标系统习惯排好列顺序，输出 `标准表.xlsx`、`标准表.xls`（老 HR 系统只认 xls）和 `异常清单.xlsx` |

两个专门处理过的细节：

- **不同系统导出的同一批数据**（ERP 导出的加班表和导入 HR 的加班表长得一样）：表头、行数、首尾行完全一致时只留第一张，避免同一批记录算两遍；
- **同类表有多张**（OA 导出的请假单和 HR 导入的请假模板）：每类只用字段最全的一张，另一张标成「备选表」，并在方案里写清楚。

字段对齐结果可以直接在页面上改，改一次就存成自定义规则（`data/bridge/rules.json`），下次遇到同一个列名自动复用，新增报表也能接。数据集现场存在 `data/bridge/datasets/{id}/meta.json`，规划方案、执行轨迹、结果统计都在里面，换台机器拷整个 `data` 目录就能接着看。

接口：

```
POST   /api/bridge/datasets                multipart，files（一个 zip 或多个表格）+ name + source
GET    /api/bridge/datasets                最近的数据集
GET    /api/bridge/datasets/{id}           数据集详情（表、方案、结果）
POST   /api/bridge/datasets/{id}/plan      {"instruction":"..."} 只规划，不执行
POST   /api/bridge/datasets/{id}/run       用户确认后执行，导出标准表与异常清单
GET    /api/bridge/datasets/{id}/rows      ?kind=ok|anomaly&page&page_size
GET    /api/bridge/datasets/{id}/download/{artifact}
GET/POST/DELETE /api/bridge/rules          自定义字段匹配规则
GET    /api/bridge/standard-fields         标准字段清单
```

### 规划器：子任务由模型拆，不是写死的

开了模型（`app.llm.enabled=true`）以后，规划阶段会把「可用工具清单 + 每张表的文件名/类型/列 + 已对齐的标准字段」一起交给模型，让它按业务目标自己排步骤、定参数，产出两部分：

- `steps`：这次调用哪几个原子能力、每步做什么。同一个数据集，问“把 HR 入职表和 ERP 薪资表桥接、算应发工资”是 8 步；改问“只整理薪资表这一张”，跨表那步自动消失，变成 7 步；
- `join_key`、`compute`、`filters`、`date_from/date_to`、`status`、`anomaly`、`output_columns`：执行阶段真正会用的参数。

模型产出的东西只在白名单里生效，边界由代码兜底：

- 工具名只认扫描、解析、识别、对齐、关联、筛选、计算、异常隔离、导出这九个；
- 计算列、异常字段、输出列只能用数据集里真的对齐上的标准字段，编出来的名字直接丢掉；
- 关联键必须真的同时出现在两张表里，否则不认；模型判定不跨表时，执行阶段就真的只处理那一张表；
- 用户在指令里没提的条件（比如“只保留在职”）不执行，只在方案备注里留一句「大模型建议的筛选…指令里没提，这次没有执行」；
- 异常检查只对参与计算的字段生效，参与加减的数值列一定带一条“非数字”检查，免得“面议”“--”算成空值却混进正常行；
- 模型调用失败、返回的不是 JSON，或者步骤少于 3 步，自动退回规则方案，页面徽标会写清是「AI 规划」还是「规则规划」。

相关代码：`service/bridge/BridgeAgentService.java`（规划器、白名单校验、执行器）、`BridgeStore.java`（目录、zip 解压、元数据与规则）、`web/BridgeController.java`；前端 `components/BridgeAgent.vue`。

## 智能体能力

汇表不是“上传文件后调一次大模型”的普通 AI 应用，而是按智能体的方式组织任务：先观察数据，再规划、查记忆、校验、修正，最后交人确认。

| 能力 | 在本项目里的落地 |
| --- | --- |
| 工具调用 | `inspect_sources` 读表结构 → `detect_intent` 识别场景 → `recall_experience` 查历史 → `apply_experience` 复用映射 → `validate_mapping` 校验 → `auto_fix` 拼接补全 → `handoff_human` 交人确认 |
| 记忆 | 有产出的任务配置写入 `agent-experience.json`，下次按“模板 + 场景 + 源表列重合度”检索并优先复用；页面可查看、可让智能体忘记 |
| 规划与反思 | 每一步都产出“做了什么、依据是什么、结论是什么”的轨迹，而不是只给一个最终 JSON |
| 执行期自愈 | 结果全为异常或 0 行时，按智能体推荐的映射重建配置重跑一次；成功后把修好的映射写回任务快照 |
| 可观测 | 配置阶段轨迹、本轮 token 与模型调用次数在新建任务页展示；全局成功率、兜底率、工具频次、token 成本在「运行观测」页汇总 |
| 人机协同 | 自动结论必须人工点“确认映射无误”才生效；必填缺失、模板和源表场景不一致会明确交回人工 |

没有配置大模型时，工具链仍然完整运行，只是语义推荐退化为规则匹配；配置模型后，模型自己决定调用哪些工具、按什么顺序、要不要重试，数值计算仍由确定性代码执行。

相关代码：`service/agent/AgentService.java`、`service/agent/ExperienceStore.java`、`service/agent/AgentToolCatalog.java`、`service/TaskRunner.java`、`web/AgentController.java`，接口为 `POST /api/agent/plan`、`GET /api/agent/experience`。

## 运行观测与审计

智能体跑得对不对、贵不贵，需要能查到数字，而不是只看一次演示。项目没有引入 Langfuse、OpenTelemetry 这类外部组件，单机就能回答四个问题：

| 页面/接口 | 看什么 |
| --- | --- |
| `/ops` 运行观测页 | 编排次数、成功率、规则兜底率、平均耗时、累计/单次 token、模型调用次数、经验命中次数 |
| 智能体自检 | 模型通道、工具注册数量、经验库条数、成功率、兜底率、审计留痕，六项给出健康度 |
| 工具注册表 | 直接反射 `@Tool` 注解，确保“文档里写的工具”和“模型能调的工具”一致 |
| 审计日志 | 生成方案、创建任务、确认执行、重跑、一句话改结果、撤销、删除，全部落盘 |

- 运行记录写入 `data/agent-runs.json`，保留最近 300 次，分页统计不读全量历史；
- 审计写入 `data/audit.log.jsonl`，只追加不改写，超过 4 MB 自动裁剪到最近 2000 行；
- 没有账号体系时，操作人取请求头 `X-Operator`，缺省记来源地址；以后接统一身份，只需让网关注入该头，日志格式不用改；
- token 由 LangChain4j 的 `ChatModelListener` 在每次模型响应时累加，供应商未上报时记 0，不编造数字。

接口为 `GET /api/ops/stats`、`GET /api/ops/tools`、`GET /api/ops/self-check`、`GET /api/ops/audit?limit=40`；相关代码在 `service/governance/`（`TokenMeter`、`AgentRunStore`、`AuditLog`）和 `web/OpsController.java`。

## 结果表分页与查询

任务记录里只留前 20 行预览，分页查询直接读任务的导出文件，所以页面看到的和下载下来的 Excel 永远是同一份数据。

| 交互 | 说明 |
| --- | --- |
| 分页 | 默认每页 10 行，可切 20 / 50 / 100；首页、末页、上一页、下一页、跳页齐全 |
| 模糊查询 | 默认全列搜索，也可以选某一列；多个关键字用空格分隔，需要同时命中 |
| 高亮 | 命中的关键字在单元格里标黄，便于核对 |
| 快捷键 | 点过表格后 `←` / `→` 翻页，`Home` / `End` 跳首末页；`Ctrl+F` 聚焦搜索框，`Esc` 清空 |
| 表格体验 | 表头吸顶、横向滚动、数字列右对齐、空值显示 `—`、长文本悬浮查看 |

接口为 `GET /api/tasks/{id}/rows?page=1&page_size=10&q=成型&column=部门`（`q`、`column` 可省略），同一任务按文件修改时间缓存，改结果重新导出后自动失效；相关代码在 `service/ResultRowsService.java` 和 `components/ResultTable.vue`。

## 结果表对话式修改

任务跑到 `done` / `warning` 之后，结果表可以继续用一句自然语言改，不需要重新配映射：

| 说法 | 智能体识别出的操作 |
| --- | --- |
| 第 3 行起始时间改成 2026-04-29 09:00 | `set`，范围是第 3 行 |
| 姓名为张三的行合计小时改成 30 | `set`，范围是按该列值匹配到的行 |
| 合计小时全部加 1 / 减去 0.5 | `math`，范围是全部行 |
| 把部门为空的记录删掉 | `delete`，范围是条件命中的行（不允许删全部） |
| 按合计小时降序排序 | `sort`，按指定列重排 |
| 新增一列「备注」填“待复核” | `add_column` / `drop_column` |

实现要点：

- 模型只做语义判断（改哪列、怎么改、什么范围），行号、条件匹配、数值换算都由确定性代码执行；
- 每句话落成一条 `task.config.edits` 记录（`instruction` / `summary` / `mode` / `changed_rows` / `ops`），重跑时按顺序重放；
- 修改后整表重算并重新导出，页面预览和下载的 Excel 始终是同一份；
- 修改有先后顺序，只支持撤销最近一条，最多保留 20 条；
- 没有配置模型或模型返回不合法时，退化为中文句式规则解析，功能仍然可用。

接口为 `POST /api/tasks/{id}/revise`（body：`{"instruction": "..."}`）和 `POST /api/tasks/{id}/undo-edit`，相关代码在 `service/ResultEditService.java`、`service/TaskService.java`、`service/TaskRunner.java`。

## 表格智作

左侧导航「表格智作」是一个独立的表格工作台：上传的、一句话新建的、多表融合出来的表都在这里，每张表就是一个 xlsx 文件，页面上看到的和下载到的始终是同一份。

| 能力 | 说明 |
| --- | --- |
| 上传 | 页面里是拖拽区，和新建任务页同一种样式：拖入或点击选择，一次可选一个或多个 `.xlsx` / `.xls`，首个工作表按首行作表头导入，重名自动加编号 |
| 一句话新建 | 「新建一张表，列：检查日期、车间、负责人、检查结果」，模型给出列名和示例行，没有模型时按列名规则兜底；输入框下的示例点了只填字，不直接发送 |
| 多表融合 | 勾选两张以上再说话，或直接在句子里点名；同一份数据按关联键左右拼（`按工号合并`），不同表结构上下拼 |
| 指定表改 | 句子里带表名就改那一张（`把4月加班明细第2行的加班时长改成4`），不带表名就在卡片下方对这张表说 |
| 改法 | `set` / `math` / `delete` / `sort` / `add_column` / `drop_column`，范围是某一行、全部行或条件命中的行 |
| 直接编辑 | 双击单元格改值、行尾删除整行、加行、加列，都即时写回 xlsx，并留一份快照可撤销 |
| 分页与查询 | 每张表各自分页（10 / 20 / 50 / 100 行）和模糊查询，全列或指定列，空格分隔多个关键字，命中高亮 |
| 仓库 | 顶部右侧「我的仓库」进入：按名称、列名搜索，打开、下载、重命名、删除，另有全量操作日志 |
| 日志 | 每张表记最近 80 条操作（上传、建表、融合、改表、改单元格、撤销），仓库右侧按时间汇总所有表 |

实现要点：

- 模型只负责「听懂」：拆出改哪列、怎么改、改哪些行；行号定位、条件匹配、数值运算由确定性代码执行，结果可预期；
- 融合的两种形态：`join` 按关联键补列，`append` 按列并集纵向拼接，源表里的空行不参与融合；
- 每张表在 `data/sheets/{id}.xlsx`，元数据在 `data/sheets.json`，撤销快照在 `data/sheet-backup/{id}.xlsx`；
- 空行在 Excel 里没有内容、读回来会被丢掉，所以行数单独记在索引里，撤销时按快照行数还原；
- 没有配置模型或模型返回不合法时，退化为中文句式规则解析，改表、建表仍然可用。

接口：

```
GET    /api/sheets?q=                       表清单（名称、列名模糊匹配）
GET    /api/sheets/templates                参考模板（接口保留，页面已不提供模板建表）
GET    /api/sheets/logs?limit=60            全局操作日志
GET    /api/sheets/{id}                     表详情（列、行数、修改记录、日志）
GET    /api/sheets/{id}/rows?page&page_size&q&column
POST   /api/sheets/upload                   multipart，字段名 files，可多个
POST   /api/sheets/from-template            {"template_id","name"}（接口保留，页面不再入口）
POST   /api/sheets/blank                    {"name","columns":[...]}
POST   /api/sheets/compose                  {"instruction","sheet_ids":[...]} 一句话建表 / 融合
POST   /api/sheets/{id}/revise              {"instruction"}
POST   /api/sheets/{id}/cell                {"row","column","value"}
POST   /api/sheets/{id}/rows                加一行
DELETE /api/sheets/{id}/rows/{row}          删一行
POST   /api/sheets/{id}/columns             {"name","value"}
POST   /api/sheets/{id}/rename              {"name"}
POST   /api/sheets/{id}/undo                撤销最近一次修改
GET    /api/sheets/{id}/download            下载 xlsx（中文文件名）
DELETE /api/sheets/{id}                     删除表及其文件
```

相关代码：`service/sheet/SheetService.java`（业务与执行）、`service/sheet/SheetAgentService.java`（语义层）、`service/sheet/SheetFile.java`（xlsx 读写）、`service/sheet/SheetTemplates.java`、`web/SheetController.java`；前端 `views/SheetWorkbench.vue`、`views/SheetRepo.vue`，表格交互复用 `components/ResultTable.vue`。

## 技术栈

| 端 | 技术 |
| --- | --- |
| 前端 | Vue 3、Vue Router、Vite、原生 CSS 设计系统 |
| 后端 | Spring Boot 3.4.5、Java 21、Maven |
| 表格 | Apache POI 5.5.1（HSSF + XSSF） |
| 语义 | LangChain4j 1.0.0（可选） |
| 存储 | 本地 JSON，无数据库 |

## 快速启动

### 方式一：只运行后端（推荐演示）

需要 JDK 21。

```powershell
cd backend
.\start.cmd
```

或者：

```powershell
cd backend
java -jar target/huibiao-backend-0.8.2.jar
```

浏览器打开 <http://127.0.0.1:8000>。前端已构建在 `frontend/dist`，后端会直接托管。

首次启动且任务为空时，会自动用 `sample_data/raw` 里的样例表跑两条示例任务，打开页面就能看到真实结果。

### 方式二：前端开发模式

需要 Node.js 18+。

```powershell
cd frontend
npm install
npm run dev
```

开发服务器默认 <http://127.0.0.1:5173>，`/api` 代理到 8000。后端仍需单独启动。

### 重新构建

```powershell
cd backend
mvn -DskipTests package

cd ..\frontend
npm run build
```

## 演示流程

1. 打开任务记录，看到两条示例任务；
2. 点“新建任务”（智能体工作台），把 `sample_data/raw` 下的 OA 请假单、年假余额和 HR 模板拖进上传区（也可以直接拖自己的表）；
3. 阶段 1「交接任务」：源表、目标模板和一句话业务口径一起交给智能体；可点快捷规则填入，高级开关里有脏数据隔离和严格格式校验；
4. 阶段 2「核对方案」：先看深色「智能体执行轨迹」（读了哪些表、命中什么经验、自动补了什么、本轮花了多少 token），再看决策摘要和字段映射；可改来源列、配拼接规则、存映射方案，确认方案是执行前的人工闸门；
5. 阶段 3「交付结果」：点“让智能体执行”，进度条走完看到 13 行输出、6 条异常、年假 46 小时、事假 144 小时；
6. 在同一屏切换“处理成功的结果表 / 异常数据”两个页签预览，并下载 `HR导入_缺勤_yyyyMMdd.xls` 和异常日志 CSV；
7. 右侧状态轨随时显示智能体状态、本轮用到的工具链和经验记忆条数；它的长期记忆可在“管理”里查看或忘记；
8. 打开「运行观测」页，看成功率、兜底率、token 成本和最近运行记录，评审时可现场刷新；
9. 侧边栏四个图标入口的地址：`/` 新建任务、`/sheets` 表格智作、`/tasks` 任务记录、`/ops` 运行观测；产品、开发、接口文档在右侧「文档」入口。

加班示例：总装车间 2026 年 4 月，36001 行源表筛出 4597 行，合计 26859.5 小时。

## 目录结构

```text
xingchen-huibiao/
├─ backend/        Spring Boot 后端、启动脚本、运行数据
├─ frontend/       Vue 3 前端、构建产物
├─ docs/           申报书、需求、产品、开发、接口文档
├─ sample_data/    比赛提供的样例表
└─ README.md
```

## 配置

`backend/src/main/resources/application.yml`：

```yaml
server:
  port: 8000

app:
  data-dir: data
  sample-dir: ../sample_data/raw
  frontend-dir: ../frontend/dist
  seed-demo: true
  llm:
    enabled: ${HUBIAO_LLM_ENABLED:false}
```

启用模型（可选，两种方式任选一种）：

**方式一：改 yml（推荐，不用改启动命令）**

编辑 `backend/config/application.yml`（没有就按下面内容新建），只填 4 行：

```yaml
app:
  llm:
    enabled: true
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1   # 换成你的服务商地址
    api-key: sk-你的密钥                                          # 只写在本机文件里
    model: qwen-plus                                             # 换成你的模型名
```

改完重启后端即可。`start.cmd` 会先 `cd` 到 `backend`，Spring Boot 默认会加载
`./config/application.yml`，其优先级高于 jar 内的 `application.yml`。
这个文件已加入 `.gitignore`，不要提交，也不要连同密钥发给别人。

**方式二：环境变量（优先级最高，适合临时切换）**

```powershell
$env:HUBIAO_LLM_ENABLED="true"
$env:HUBIAO_LLM_BASE_URL="http://127.0.0.1:11434/v1"
$env:HUBIAO_LLM_API_KEY="not-needed"
$env:HUBIAO_LLM_MODEL="qwen2.5:14b"
java -jar backend/target/huibiao-backend-0.8.2.jar
```

模型只用于补充场景判断，不参与数值计算。未配置或调用失败时自动退回规则解析。
是否生效看启动日志「启用 LangChain4j 语义解析」，或访问 `/api/health` 的 `llm_enabled`。

## 数据说明

| 路径 | 内容 |
| --- | --- |
| `backend/data/uploads/` | 上传源文件副本 |
| `backend/data/outputs/{任务编号}/` | HR 导入表、备份表、异常日志 |
| `backend/data/tasks.json` | 任务记录 |
| `backend/data/files.json` | 文件登记 |
| `backend/data/settings.json` | 规则、模板、字段别名 |
| `backend/data/agent-experience.json` | 智能体经验记忆（成功的场景 + 模板 + 映射） |
| `backend/data/sheets/{id}.xlsx` | 表格智作里的每张表，一个文件一张 |
| `backend/data/sheets.json` | 表格清单：名称、来源、列、行数、修改记录、日志 |
| `backend/data/sheet-backup/{id}.xlsx` | 撤销用的上一版快照 |

任务记录只增不删，避免误删已经交给 HR 的结果。备份时直接拷贝整个 `backend/data` 即可。

## 比赛文档

`docs/` 下是五份文档的 Markdown 版本，`docs/docx/` 是对应 Word 版本：

1. 项目申报书
2. 需求分析文档
3. 产品设计文档
4. 开发文档
5. 接口文档

接口文档另有可在浏览器打开的静态版本：后端启动后访问 `/docs/`。

## 已知限制

- 只处理第一个工作表；
- CSV 只支持逗号分隔；
- 任务配置用 JSON 全量读写，任务很多时需要换数据库；
- 没有登录和权限，默认只监听本机；
- 年假余额不回写，只做单次结算；
- 10 万行以上未做压力测试；
- 智能体模式的计算口径是“同名列累加 + 加减公式”，模型能把业务目标翻译成 join 键、计算列、筛选条件和异常检查，但还不能生成任意 group by / 多级汇总代码（这一步仍走确定性的七个原子能力，保证结果可复现）。

这些限制在开发文档的“后续计划”里按优先级列出了。

## 团队

星尘汇表智联 · 4 人

- 需求与业务规则：【姓名】
- 前端：【姓名】
- 后端：【姓名】
- 测试与文档：【姓名】

指导老师：【姓名】

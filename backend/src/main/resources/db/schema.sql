-- 汇表智能体本地库结构（SQLite 3）
-- 设计口径：会被筛选、排序、分页、统计的字段单独成列；只有深层嵌套的快照才放 JSON 列。
-- 表格本体仍然存 data/sheets/{id}.xlsx，库里只放索引与日志，下载到的文件和页面看到的一致。

-- 任务：列表按时间倒序、按状态筛选都走 SQL，body 是任务完整快照（源表、产物、预览、异常等嵌套结构）
CREATE TABLE IF NOT EXISTS tasks (
    id           TEXT PRIMARY KEY,
    name         TEXT,
    status       TEXT,
    created_at   TEXT,
    finished_at  TEXT,
    body         TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tasks_created ON tasks (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks (status);

-- 表格仓库：列较多但都是平铺字段，便于按名称/来源搜索和统计
CREATE TABLE IF NOT EXISTS sheets (
    id             TEXT PRIMARY KEY,
    name           TEXT,
    origin         TEXT,
    original_name  TEXT,
    task_id        TEXT,
    note           TEXT,
    created_at     TEXT,
    updated_at     TEXT,
    columns        TEXT,
    row_count      INTEGER,
    revision_count INTEGER,
    last_summary   TEXT,
    undo_available INTEGER,
    undo_row_count INTEGER,
    revisions      TEXT
);
CREATE INDEX IF NOT EXISTS idx_sheets_updated ON sheets (updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_sheets_name ON sheets (name);
CREATE INDEX IF NOT EXISTS idx_sheets_task ON sheets (task_id);

-- 表格操作日志：全局只留最近 100 条；deleted=1 表示表已删除、日志仍要留痕
CREATE TABLE IF NOT EXISTS sheet_logs (
    id         TEXT PRIMARY KEY,
    sheet_id   TEXT,
    sheet_name TEXT,
    time       TEXT,
    action     TEXT,
    detail     TEXT,
    level      TEXT,
    deleted    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_sheet_logs_time ON sheet_logs (time DESC);
CREATE INDEX IF NOT EXISTS idx_sheet_logs_sheet ON sheet_logs (sheet_id, deleted);

-- 智能体运行观测：全部字段平铺，成功率/兜底率/Token 直接在 SQL 里聚合
CREATE TABLE IF NOT EXISTS agent_runs (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    at             TEXT,
    status         TEXT,
    mode           TEXT,
    fallback       INTEGER,
    files          INTEGER,
    scene          TEXT,
    scene_name     TEXT,
    template       TEXT,
    mappings       INTEGER,
    steps          INTEGER,
    fixes          INTEGER,
    tokens_in      INTEGER,
    tokens_out     INTEGER,
    model_calls    INTEGER,
    duration_ms    INTEGER,
    experience_hit INTEGER,
    note           TEXT,
    tools          TEXT
);
CREATE INDEX IF NOT EXISTS idx_runs_at ON agent_runs (at DESC);

-- 审计日志：只追加，最多留 100 条；extra 存附加字段
CREATE TABLE IF NOT EXISTS audit_logs (
    id     INTEGER PRIMARY KEY AUTOINCREMENT,
    at     TEXT,
    action TEXT,
    target TEXT,
    detail TEXT,
    actor  TEXT,
    extra  TEXT
);
CREATE INDEX IF NOT EXISTS idx_audit_at ON audit_logs (at DESC);

-- 智能体模式的数据集现场：规划方案、执行结果等深层嵌套结构放 body，
-- 编号/名称/来源/时间/状态单独成列，列表按时间倒序就是一条 SQL
CREATE TABLE IF NOT EXISTS bridge_datasets (
    id          TEXT PRIMARY KEY,
    name        TEXT,
    source_type TEXT,
    created_at  TEXT,
    status      TEXT,
    instruction TEXT,
    body        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bridge_created ON bridge_datasets (created_at DESC);

-- 小型配置类数据（映射方案、经验记忆、文件索引、设置）：整体读写，用一张键值表承载
CREATE TABLE IF NOT EXISTS kv (
    scope      TEXT PRIMARY KEY,
    body       TEXT NOT NULL,
    updated_at TEXT
);

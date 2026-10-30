package com.xingchen.huibiao.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 本地唯一的持久层：data/huibiao.db（SQLite）。
@Component
public class SqliteDb {

    private static final Logger log = LoggerFactory.getLogger(SqliteDb.class);

    // 一次数据库操作的函数式接口，参数是已经拿到的连接。
    public interface SqlFn<T> {
        T apply(Connection connection) throws SQLException;
    }

    // 把一行结果集映射成对象。
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final DataPaths paths;
    private final ObjectMapper mapper;
    private SQLiteDataSource dataSource;

    public SqliteDb(DataPaths paths, ObjectMapper mapper) {
        this.paths = paths;
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        Path file = paths.dbFile();
        // 连接串里的 PRAGMA 对每条新连接生效：WAL + 5 秒写锁等待 + NORMAL 落盘，兼顾安全与速度
        String url = "jdbc:sqlite:" + file.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=on";
        dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        try {
            createSchema();
        } catch (Exception e) {
            throw new IllegalStateException("初始化数据库失败：" + file, e);
        }
        log.info("本地数据库已就绪：{}（WAL 模式，读不挡写）", file);
    }

    public Path dbFile() {
        return paths.dbFile();
    }

    private void createSchema() {
        String script;
        try (InputStream in = new ClassPathResource("db/schema.sql").getInputStream()) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取建表脚本失败：" + e.getMessage(), e);
        }
        read(connection -> {
            try (Statement st = connection.createStatement()) {
                for (String part : script.split(";")) {
                    String sql = part.trim();
                    if (sql.isEmpty()) {
                        continue;
                    }
                    // 每条语句前面可能带若干行注释，先把注释行去掉，剩下的非空才是真正要执行的 DDL
                    StringBuilder clean = new StringBuilder();
                    for (String line : sql.split("\n")) {
                        if (!line.trim().startsWith("--")) {
                            clean.append(line).append('\n');
                        }
                    }
                    String ddl = clean.toString().trim();
                    if (!ddl.isEmpty()) {
                        st.execute(ddl);
                    }
                }
            }
            return null;
        });
    }

    // 只读查询：连接用完就关，多个请求可以同时读。
    public <T> T read(SqlFn<T> fn) {
        try (Connection connection = dataSource.getConnection()) {
            return fn.apply(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("数据库读取失败：" + e.getMessage(), e);
        }
    }

    // 写入：整段包在一个事务里，要么全成功，要么回滚到操作前。
    public <T> T tx(SqlFn<T> fn) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = fn.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // 回滚失败也把原始错误抛出去，不然排查时会丢掉根因
                }
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("数据库写入失败：" + e.getMessage(), e);
        }
    }

    public int update(String sql, Object... args) {
        return tx(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(ps, args);
                return ps.executeUpdate();
            }
        });
    }

    public long count(String sql, Object... args) {
        Long value = one(sql, rs -> rs.getLong(1), args);
        return value == null ? 0 : value;
    }

    public <T> List<T> list(String sql, RowMapper<T> rowMapper, Object... args) {
        return read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(rowMapper.map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public <T> T one(String sql, RowMapper<T> rowMapper, Object... args) {
        List<T> rows = list(sql, rowMapper, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // 表里一条都没有：用来判断要不要把老版本的 JSON 数据导进来。
    public boolean isEmpty(String table) {
        return count("SELECT COUNT(*) FROM " + table) == 0;
    }

    public String kv(String scope) {
        return one("SELECT body FROM kv WHERE scope = ?", rs -> rs.getString(1), scope);
    }

    public void kvPut(String scope, Object value) {
        update("INSERT INTO kv (scope, body, updated_at) VALUES (?, ?, datetime('now','localtime')) "
                        + "ON CONFLICT(scope) DO UPDATE SET body = excluded.body, updated_at = excluded.updated_at",
                scope, toJson(value));
    }

    // 一次性状态标记（例如「示例任务是否已经初始化过」）。
    public boolean flag(String scope) {
        return "1".equals(kv(scope));
    }

    public void setFlag(String scope, boolean value) {
        kvPut(scope, value ? 1 : 0);
    }

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败：" + e.getMessage(), e);
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("JSON 解析失败，按空值处理：{}", e.getMessage());
            return null;
        }
    }

    public <T> List<T> jsonList(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (Exception e) {
            log.warn("JSON 列表解析失败，按空列表处理：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // 列定义、修改记录这类「数组套对象」的结构：解析成 Map 列表，前端直接原样展示。
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> jsonMapList(String json) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) (List<?>) jsonList(json, Map.class);
        return list == null ? new ArrayList<>() : list;
    }

    // 单个 JSON 对象（例如审计日志的附加字段），解析失败按空 Map 处理。
    @SuppressWarnings("unchecked")
    public Map<String, Object> jsonMap(String json) {
        Map<String, Object> map = (Map<String, Object>) fromJson(json, Map.class);
        return map == null ? new LinkedHashMap<>() : map;
    }

    // 老版本把数据写成 JSON 文件。导入成功后把原文件改名成 *.migrated 留一份，
    public boolean retireLegacy(Path legacy) {
        if (legacy == null || !Files.isRegularFile(legacy)) {
            return false;
        }
        try {
            Path target = legacy.resolveSibling(legacy.getFileName() + ".migrated");
            Files.move(legacy, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("旧数据已导入数据库，原文件留档：{}", target.getFileName());
            return true;
        } catch (IOException e) {
            log.warn("旧数据留档失败：{}", e.getMessage());
            return false;
        }
    }

    private void bind(PreparedStatement ps, Object... args) throws SQLException {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            int index = i + 1;
            if (value == null) {
                ps.setObject(index, null);
            } else if (value instanceof Boolean flag) {
                ps.setInt(index, flag ? 1 : 0);
            } else if (value instanceof Integer || value instanceof Long || value instanceof Short) {
                ps.setLong(index, ((Number) value).longValue());
            } else {
                ps.setString(index, String.valueOf(value));
            }
        }
    }
}

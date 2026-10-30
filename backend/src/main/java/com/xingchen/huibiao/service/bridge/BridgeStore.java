package com.xingchen.huibiao.service.bridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.config.SqliteDb;
import com.xingchen.huibiao.model.BridgeDataset;
import com.xingchen.huibiao.util.TextUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// 智能体模式的数据集仓库：表本体（xlsx/csv）摊在 data/bridge/datasets/&lt;
@Service
public class BridgeStore {

    private static final Logger log = LoggerFactory.getLogger(BridgeStore.class);
    private static final Set<String> TABLE_EXT = Set.of(".xlsx", ".xls", ".csv");
    private static final int MAX_FILES = 300;
    // 最近记录最多返回 100 条，与页面上的清理入口配合
    private static final int MAX_RECENT = 100;
    private static final long MAX_ENTRY_BYTES = 40L * 1024 * 1024;
    // 国内压缩工具默认按 GBK 存文件名，GB18030 是它的超集，兜底时用它
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // 自定义匹配规则是整体读写的小数据，放 kv 表
    private static final String RULES_SCOPE = "bridge_rules";

    private final DataPaths paths;
    private final SqliteDb db;
    private final ObjectMapper mapper;

    public BridgeStore(DataPaths paths, SqliteDb db, ObjectMapper mapper) {
        this.paths = paths;
        this.db = db;
        this.mapper = mapper;
    }

    // 老版本把每个数据集的现场写成 datasets/&lt;id&gt;/meta.json、规则写成 bridge/rules.json。
    @PostConstruct
    public void init() {
        migrateLegacyDatasets();
        migrateLegacyRules();
    }

    private void migrateLegacyDatasets() {
        if (!db.isEmpty("bridge_datasets")) {
            return;
        }
        Path base = root().resolve("datasets");
        if (!Files.isDirectory(base)) {
            return;
        }
        List<BridgeDataset> loaded = new ArrayList<>();
        List<Path> legacyFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
            for (Path child : stream) {
                Path meta = child.resolve("meta.json");
                if (!Files.isDirectory(child) || !Files.isRegularFile(meta)) {
                    continue;
                }
                try {
                    BridgeDataset dataset = mapper.readValue(meta.toFile(), BridgeDataset.class);
                    if (dataset != null && !TextUtils.norm(dataset.id).isEmpty()) {
                        loaded.add(dataset);
                        legacyFiles.add(meta);
                    }
                } catch (IOException ignored) {
                    // 半途写坏的历史文件跳过，不影响其他数据集
                }
            }
        } catch (IOException e) {
            log.warn("读取旧数据集目录失败：{}", e.getMessage());
            return;
        }
        if (loaded.isEmpty()) {
            return;
        }
        db.tx(connection -> {
            for (BridgeDataset dataset : loaded) {
                upsert(connection, dataset);
            }
            return null;
        });
        legacyFiles.forEach(db::retireLegacy);
        log.info("已把 {} 个旧数据集现场导入数据库", loaded.size());
    }

    private void migrateLegacyRules() {
        Path file = rulesFile();
        if (db.kv(RULES_SCOPE) != null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            List<Map<String, String>> rules = mapper.readValue(file.toFile(), new TypeReference<>() {
            });
            db.kvPut(RULES_SCOPE, rules == null ? new ArrayList<>() : rules);
            db.retireLegacy(file);
            log.info("已把旧自定义匹配规则导入数据库");
        } catch (Exception e) {
            log.warn("旧自定义规则导入失败，先按空规则启动：{}", e.getMessage());
        }
    }

    public Path root() {
        return paths.root().resolve("bridge");
    }

    public Path datasetDir(String id) {
        return root().resolve("datasets").resolve(id).normalize();
    }

    public Path filesDir(String id) {
        return datasetDir(id).resolve("files");
    }

    public Path outDir(String id) {
        return datasetDir(id).resolve("out");
    }

    // 自定义字段匹配规则：标准字段 ← 各系统里的叫法，跨数据集复用
    private Path rulesFile() {
        return root().resolve("rules.json");
    }

    public String newId() {
        return "D" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    public String now() {
        return LocalDateTime.now().format(TS);
    }

    public void save(BridgeDataset dataset) {
        try {
            Files.createDirectories(datasetDir(dataset.id));
            Files.createDirectories(outDir(dataset.id));
        } catch (IOException e) {
            throw new IllegalStateException("创建数据集目录失败：" + e.getMessage(), e);
        }
        db.tx(connection -> {
            upsert(connection, dataset);
            return null;
        });
    }

    private void upsert(java.sql.Connection connection, BridgeDataset dataset) throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO bridge_datasets (id, name, source_type, created_at, status, instruction, body) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET name = excluded.name, source_type = excluded.source_type, "
                        + "created_at = excluded.created_at, status = excluded.status, "
                        + "instruction = excluded.instruction, body = excluded.body")) {
            ps.setString(1, dataset.id);
            ps.setString(2, dataset.name == null ? "" : dataset.name);
            ps.setString(3, dataset.sourceType == null ? "files" : dataset.sourceType);
            ps.setString(4, dataset.createdAt == null ? "" : dataset.createdAt);
            ps.setString(5, dataset.status == null ? "" : dataset.status);
            ps.setString(6, dataset.instruction == null ? "" : dataset.instruction);
            ps.setString(7, db.toJson(dataset));
            ps.executeUpdate();
        }
    }

    public BridgeDataset load(String id) {
        String body = db.one("SELECT body FROM bridge_datasets WHERE id = ?", rs -> rs.getString(1), id);
        if (body == null) {
            throw new IllegalArgumentException("数据集不存在，可能已被清理，请重新上传");
        }
        BridgeDataset dataset = db.fromJson(body, BridgeDataset.class);
        if (dataset == null) {
            throw new IllegalStateException("读取数据集失败：快照内容损坏");
        }
        return dataset;
    }

    // 最近上传的数据集，给页面做“继续上一单”
    public List<BridgeDataset> recent() {
        List<BridgeDataset> list = db.list(
                "SELECT body FROM bridge_datasets ORDER BY created_at DESC LIMIT ?",
                rs -> db.fromJson(rs.getString(1), BridgeDataset.class), MAX_RECENT);
        list.removeIf(java.util.Objects::isNull);
        return list;
    }

    // 删除一个数据集：整目录连同源表、方案与产出一起清掉，数据库里的现场一起删。
    public synchronized void deleteOne(String id) {
        if (TextUtils.norm(id).isEmpty()) {
            return;
        }
        Path base = root().resolve("datasets").normalize();
        Path dir = datasetDir(id);
        if (!dir.startsWith(base) || dir.equals(base)) {
            throw new IllegalArgumentException("数据集编号不合法");
        }
        deleteTree(dir);
        db.update("DELETE FROM bridge_datasets WHERE id = ?", id);
    }

    // 批量删除；ids 为空表示清空全部数据集。返回实际删除的个数。
    public synchronized int delete(List<String> ids) {
        Path base = root().resolve("datasets").normalize();
        if (ids == null || ids.isEmpty()) {
            int count = 0;
            if (Files.exists(base)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
                    for (Path child : stream) {
                        if (Files.isDirectory(child)) {
                            deleteTree(child);
                            count++;
                        }
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("清空数据集失败：" + e.getMessage(), e);
                }
            }
            // 目录和记录一起清，两边条数不一致时以多的为准，保证界面显示真的删干净了
            long rows = db.count("SELECT COUNT(*) FROM bridge_datasets");
            db.update("DELETE FROM bridge_datasets");
            return (int) Math.max(count, rows);
        }
        int count = 0;
        for (String id : ids) {
            if (TextUtils.norm(id).isEmpty()) {
                continue;
            }
            Path dir = datasetDir(id);
            if (!dir.startsWith(base) || dir.equals(base)) {
                continue;
            }
            if (Files.exists(dir)) {
                deleteTree(dir);
                count++;
            }
            db.update("DELETE FROM bridge_datasets WHERE id = ?", id);
        }
        return count;
    }

    private void deleteTree(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new IllegalStateException("删除数据集失败：" + e.getMessage(), e);
        }
    }

    // 把上传内容摊到 files/ 下：zip 解压、文件夹里选中的文件逐个复制，只留表格。
    public int materialize(String datasetId, List<MultipartFile> uploads) {
        Path dir = filesDir(datasetId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("创建数据集目录失败：" + e.getMessage(), e);
        }
        int saved = 0;
        for (MultipartFile upload : uploads == null ? List.<MultipartFile>of() : uploads) {
            if (upload == null || upload.isEmpty() || saved >= MAX_FILES) continue;
            String original = upload.getOriginalFilename() == null ? "upload.xlsx" : upload.getOriginalFilename();
            original = original.replace('\\', '/');
            String name = original.substring(original.lastIndexOf('/') + 1);
            if (name.isEmpty() || name.startsWith("~$") || name.startsWith(".")) continue;
            byte[] bytes;
            try {
                bytes = upload.getBytes();
            } catch (IOException e) {
                throw new IllegalStateException("读取上传文件失败：" + e.getMessage(), e);
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".zip")) {
                saved += unzip(bytes, dir);
            } else if (hasTableExt(name)) {
                writeFile(dir, name, bytes);
                saved++;
            }
        }
        return saved;
    }

    // 先只看条目名判断压缩包用的是哪套编码：国内压缩工具大多按 GBK 写文件名，
    // 所以先按 UTF-8 试，解不出来再换 GB18030
    private Charset zipCharset(byte[] bytes) {
        try (ZipInputStream probe = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            while (probe.getNextEntry() != null) {
                // 只解条目名、不读内容，名字全部解得出来就说明是 UTF-8 包
            }
            return StandardCharsets.UTF_8;
        } catch (IOException | RuntimeException e) {
            log.info("压缩包文件名不是 UTF-8，改用 GB18030 重试：{}", e.getMessage());
            return GB18030;
        }
    }

    private int unzip(byte[] bytes, Path target) {
        Charset charset = zipCharset(bytes);
        int count = unzip(bytes, target, charset);
        if (count == 0 && StandardCharsets.UTF_8.equals(charset)) {
            // 名字里混了两种编码、恰好没被探测到的情况，用 GB18030 再解一次兜底
            count = unzip(bytes, target, GB18030);
        }
        return count;
    }

    private int unzip(byte[] bytes, Path target, Charset charset) {
        int count = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes), charset)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null && count < MAX_FILES) {
                if (entry.isDirectory()) continue;
                String raw = entry.getName().replace('\\', '/');
                if (raw.startsWith("__MACOSX/") || raw.contains("/.")) continue;
                String name = raw.substring(raw.lastIndexOf('/') + 1);
                if (name.isEmpty() || name.startsWith("~$") || name.startsWith(".") || !hasTableExt(name)) continue;
                if (entry.getSize() > MAX_ENTRY_BYTES) continue;
                byte[] data = readLimited(zin, MAX_ENTRY_BYTES);
                if (data.length == 0) continue;
                writeFile(target, name, data);
                count++;
            }
        } catch (IOException | RuntimeException e) {
            // 加密压缩包、损坏的包都在这里落地，给一句能照着做的提示，不要抛英文的 JDK 异常
            throw new IllegalStateException("压缩包解析失败：" + e.getMessage()
                    + "。如果压缩包设置了密码或格式不常见，请先解压成表格再上传", e);
        }
        return count;
    }

    private byte[] readLimited(InputStream in, long limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > limit) break;
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // 同名文件加序号，避免后上传的把前面的覆盖掉
    private void writeFile(Path dir, String name, byte[] bytes) {
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        Path target = dir.resolve(TextUtils.safeName(base + ext));
        int index = 2;
        while (Files.exists(target)) {
            target = dir.resolve(TextUtils.safeName(base + "_" + index + ext));
            index++;
        }
        try {
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new IllegalStateException("保存表格失败：" + e.getMessage(), e);
        }
    }

    public List<Path> tableFiles(String datasetId) {
        List<Path> files = new ArrayList<>();
        Path dir = filesDir(datasetId);
        if (!Files.exists(dir)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (Files.isRegularFile(child) && hasTableExt(child.getFileName().toString())) {
                    files.add(child);
                }
            }
        } catch (IOException e) {
            return files;
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    public static boolean hasTableExt(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return TABLE_EXT.stream().anyMatch(lower::endsWith);
    }

    // 自定义匹配规则

    public List<Map<String, String>> rules() {
        String body = db.kv(RULES_SCOPE);
        if (body == null || body.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, String>> rules = mapper.readValue(body, new TypeReference<>() {
            });
            return rules == null ? new ArrayList<>() : rules;
        } catch (Exception e) {
            log.warn("自定义匹配规则解析失败，按空规则处理：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // 同一个别名只留一条，重复保存时按最新覆盖
    public synchronized List<Map<String, String>> addRule(String standard, String alias) {
        List<Map<String, String>> rules = rules();
        String key = TextUtils.norm(alias);
        rules.removeIf(item -> TextUtils.norm(item.get("alias")).equals(key));
        Map<String, String> rule = new LinkedHashMap<>();
        rule.put("standard", standard);
        rule.put("alias", alias);
        rule.put("at", now());
        rules.add(rule);
        db.kvPut(RULES_SCOPE, rules);
        return rules;
    }

    public synchronized List<Map<String, String>> removeRule(String alias) {
        List<Map<String, String>> rules = rules();
        rules.removeIf(item -> TextUtils.norm(item.get("alias")).equals(TextUtils.norm(alias)));
        db.kvPut(RULES_SCOPE, rules);
        return rules;
    }
}

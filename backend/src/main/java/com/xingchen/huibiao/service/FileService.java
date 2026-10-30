package com.xingchen.huibiao.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.config.SqliteDb;
import com.xingchen.huibiao.model.FileMeta;
import com.xingchen.huibiao.util.TextUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// 上传文件落盘 + 元信息登记。源文件只读，处理时另开工作簿，不会改到原表。
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    private static final String SCOPE = "files";

    private static final Map<String, String> ROLE_LABELS = Map.of(
            "auto", "自动识别",
            "leave_request", "OA 请假单",
            "annual_balance", "年假余额",
            "overtime", "ERP 加班表",
            "target_template", "HR 目标模板",
            "unknown", "普通源表"
    );

    private final DataPaths paths;
    private final SqliteDb db;
    private final ObjectMapper mapper;
    private final ExcelService excel;
    private final HeaderCatalog catalog;
    private final SettingsService settings;
    private final List<FileMeta> files = new ArrayList<>();

    // 解析结果短缓存：一次规划里智能体和意图识别会各读一遍同一张表，缓存后只解析一次
    private static final long SHEET_CACHE_TTL_MS = 60_000L;
    private final Map<String, CachedSheet> sheetCache = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedSheet(long at, long modified, int config, ExcelService.SheetData data) {
    }

    public FileService(DataPaths paths, SqliteDb db, ObjectMapper mapper, ExcelService excel,
                       HeaderCatalog catalog, SettingsService settings) {
        this.paths = paths;
        this.db = db;
        this.mapper = mapper;
        this.excel = excel;
        this.catalog = catalog;
        this.settings = settings;
    }

    @PostConstruct
    public synchronized void load() {
        migrateLegacy();
        files.clear();
        String body = db.kv(SCOPE);
        if (body != null && !body.isBlank()) {
            try {
                List<FileMeta> loaded = mapper.readValue(body, new TypeReference<List<FileMeta>>() {
                });
                if (loaded != null) {
                    files.addAll(loaded);
                }
            } catch (Exception e) {
                log.warn("文件索引读取失败：{}", e.getMessage());
            }
        }
        backfillDigests();
    }

    // 老版本的 files.json 只在库里没有这份数据时导入一次，导完改名留档。
    private void migrateLegacy() {
        Path file = paths.filesFile();
        if (db.kv(SCOPE) != null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            List<FileMeta> loaded = mapper.readValue(file.toFile(), new TypeReference<List<FileMeta>>() {
            });
            db.kvPut(SCOPE, loaded == null ? new ArrayList<>() : loaded);
            log.info("已把 {} 条旧文件索引导入数据库", loaded == null ? 0 : loaded.size());
            db.retireLegacy(file);
        } catch (Exception e) {
            log.warn("旧文件索引导入失败，先按空索引启动：{}", e.getMessage());
        }
    }

    // 给历史记录补上内容指纹：老的 files.json 没有 digest，补完之后新上传就能按内容判定重复。
    private void backfillDigests() {
        int backfilled = 0;
        int duplicates = 0;
        Map<String, FileMeta> seen = new LinkedHashMap<>();
        for (FileMeta meta : files) {
            if (meta.digest == null || meta.digest.isEmpty()) {
                String digest = digestOfFile(pathOf(meta));
                if (!digest.isEmpty()) {
                    meta.digest = digest;
                    backfilled++;
                }
            }
            if (meta.digest != null) {
                FileMeta first = seen.putIfAbsent(meta.digest, meta);
                if (first != null) {
                    duplicates++;
                }
            }
        }
        if (backfilled > 0) {
            save();
            log.info("为 {} 个历史文件补算了内容指纹", backfilled);
        }
        if (duplicates > 0) {
            log.info("文件索引里有 {} 条历史重复记录（内容相同），已保留以兼容旧任务；新上传不会再重复登记", duplicates);
        }
    }

    // 整份索引一次原子写入：数据库要么是写入前的样子，要么是写入后的样子。
    private synchronized void save() {
        try {
            db.kvPut(SCOPE, files);
        } catch (RuntimeException e) {
            throw new IllegalStateException("保存文件索引失败：" + e.getMessage(), e);
        }
    }

    // 上传结果：duplicate=true 表示这个文件内容之前已经登记过，直接复用了已有那条。
    public record UploadResult(FileMeta file, boolean duplicate) {
    }

    public UploadResult upload(MultipartFile multipart, String role) {
        if (multipart == null || multipart.isEmpty()) {
            throw new IllegalArgumentException("文件是空的");
        }
        String original = multipart.getOriginalFilename() == null ? "upload.xlsx" : multipart.getOriginalFilename();
        String ext = extOf(original);
        if (!List.of(".xlsx", ".xls", ".csv").contains(ext)) {
            throw new IllegalArgumentException("只支持 .xlsx / .xls / .csv");
        }
        byte[] bytes;
        try {
            bytes = multipart.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败：" + e.getMessage(), e);
        }
        String digest = digestOf(bytes);
        // 内容跟已登记的文件一模一样，就不新加记录了，改成把那几条旧记录覆盖一遍：
        // 换上新字节，再按现在的规则重算表头，免得旧记录挂着过期的解析结果
        List<FileMeta> same = findAllByDigest(digest, bytes.length);
        if (!same.isEmpty()) {
            FileMeta target = same.get(0);
            for (FileMeta meta : same) {
                if (original.equals(meta.name)) {
                    target = meta;
                    break;
                }
            }
            // 同内容的旧记录可能几十条（ERP 加班表就有 24 条、3 万行），表只解析一次，
            // 剩下的复用结果，不然上传接口要卡十几秒
            FileMeta parsedOnce = null;
            for (FileMeta meta : same) {
                parsedOnce = overwrite(meta, bytes, meta.name, role, digest, parsedOnce);
            }
            // 同内容的历史记录可能有很多条，逐条落盘太慢，统一写一次索引
            synchronized (this) {
                save();
            }
            log.info("文件 {} 与已登记的 {} 条记录内容一致，已逐条覆盖并重新解析", original, same.size());
            return new UploadResult(target, true);
        }
        String fileId = "F" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String storedName = fileId + ext;
        Path target = paths.uploadPath(storedName);
        try {
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("文件保存失败：" + e.getMessage(), e);
        }
        return new UploadResult(register(fileId, original, storedName, target, bytes.length, role, digest), false);
    }

    // 按内容指纹找已登记的文件；旧记录没有指纹时按同大小重算一次并补写回去。
    private FileMeta findByDigest(String digest, long size) {
        List<FileMeta> all = findAllByDigest(digest, size);
        return all.isEmpty() ? null : all.get(0);
    }

    // 找出所有内容一致的历史记录（历史上重复上传可能登记过多条）。
    private List<FileMeta> findAllByDigest(String digest, long size) {
        List<FileMeta> matched = new ArrayList<>();
        synchronized (this) {
            for (FileMeta meta : files) {
                if (digest.equals(meta.digest)) {
                    matched.add(meta);
                }
            }
            boolean dirty = false;
            for (FileMeta meta : files) {
                if (meta.digest == null && meta.size == size) {
                    String existing = digestOfFile(pathOf(meta));
                    if (digest.equals(existing)) {
                        meta.digest = existing;
                        dirty = true;
                        matched.add(meta);
                    }
                }
            }
            if (dirty) {
                save();
            }
        }
        return matched;
    }

    private String digestOf(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(bytes);
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("计算文件指纹失败", e);
        }
    }

    private String digestOfFile(Path path) {
        try {
            return digestOf(Files.readAllBytes(path));
        } catch (IOException e) {
            return "";
        }
    }

    // 示例数据也从这里走，保证和用户上传的文件是同一条处理路径。
    public FileMeta registerExisting(Path source, String role) {
        String original = source.getFileName().toString();
        String ext = extOf(original);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(source);
        } catch (IOException e) {
            throw new IllegalStateException("示例文件读取失败：" + e.getMessage(), e);
        }
        String digest = digestOf(bytes);
        FileMeta same = findByDigest(digest, bytes.length);
        if (same != null) {
            return same;
        }
        String fileId = "F" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String storedName = fileId + ext;
        Path target = paths.uploadPath(storedName);
        try {
            Files.write(target, bytes);
            return register(fileId, original, storedName, target, bytes.length, role, digest);
        } catch (IOException e) {
            throw new IllegalStateException("示例文件复制失败：" + e.getMessage(), e);
        }
    }

    private FileMeta register(String fileId, String original, String storedName, Path target, long size,
                              String role, String digest) {
        ExcelService.SheetData sheet = excel.read(target, catalog, settings.mergedAliases(),
                settings.boolRule("auto_header", true));

        FileMeta meta = new FileMeta();
        meta.fileId = fileId;
        meta.storedName = storedName;
        applyParsed(meta, sheet, role, size, digest);
        meta.name = original;
        meta.ext = extOf(original);

        synchronized (this) {
            files.add(meta);
            save();
        }
        log.info("登记文件 {}（{}，{} 行）", meta.name, meta.roleLabel, sheet.rows().size());
        return meta;
    }

    // 覆盖旧记录：用新字节替换物理文件，再按现在的规则重算表头、样例和场景；
    // parsedOnce 不为空就直接拿它，省得每条都重新解析一遍
    private FileMeta overwrite(FileMeta meta, byte[] bytes, String original, String role, String digest,
                               FileMeta parsedOnce) {
        Path target = paths.uploadPath(meta.storedName);
        try {
            // 内容一致就不重复落盘（大表有几万行、几十兆，反复写很慢）；
            // 大小对得上时再核一次指纹，避免同大小的坏文件被放过
            boolean needWrite = true;
            if (Files.exists(target) && Files.size(target) == bytes.length) {
                needWrite = !digest.equals(digestOfFile(target));
            }
            if (needWrite) {
                Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("文件覆盖失败：" + e.getMessage(), e);
        }
        synchronized (this) {
            meta.name = original;
            meta.size = bytes.length;
            meta.digest = digest;
            meta.path = "uploads/" + meta.storedName;
            if (parsedOnce == null) {
                // 走带缓存的读表：物理文件没变（内容一致跳过重写）时可以直接复用上次解析
                ExcelService.SheetData sheet = readSheet(meta);
                applyParsed(meta, sheet, role, bytes.length, digest);
                log.info("覆盖文件 {}（{}，{} 行）", meta.name, meta.roleLabel, sheet.rows().size());
            } else {
                // 内容相同，解析结果完全一致，复制一份避免多处共享同一引用
                meta.headerRow = parsedOnce.headerRow;
                meta.columns = new ArrayList<>(parsedOnce.columns);
                meta.sheets = new ArrayList<>(parsedOnce.sheets);
                meta.sample = new LinkedHashMap<>(parsedOnce.sample);
                meta.role = parsedOnce.role;
                meta.roleLabel = parsedOnce.roleLabel;
            }
        }
        // 缓存由 readSheet 按文件修改时间自动失效，这里不用再手动清
        return meta;
    }

    // 把解析结果写进元数据：表头行、列、工作表清单、样例值、场景标签。
    private void applyParsed(FileMeta meta, ExcelService.SheetData sheet, String role, long size, String digest) {
        meta.size = size;
        meta.digest = digest;
        meta.headerRow = sheet.headerRow() + 1;
        meta.columns = sheet.headers();
        meta.sheets = new ArrayList<>();
        for (FileMeta.SheetInfo info : sheet.sheets()) {
            if (info.name.equals(sheet.sheetName())) {
                meta.sheets.add(new FileMeta.SheetInfo(info.name, sheet.rows().size(), sheet.headers().size()));
            } else {
                meta.sheets.add(info);
            }
        }
        meta.sample = sampleOf(sheet);
        meta.role = normalizeRole(role);
        if ("auto".equals(meta.role)) {
            meta.role = detectRole(sheet.headers());
        }
        meta.roleLabel = ROLE_LABELS.getOrDefault(meta.role, "普通源表");
    }

    private Map<String, String> sampleOf(ExcelService.SheetData sheet) {
        Map<String, String> sample = new LinkedHashMap<>();
        for (String header : sheet.headers()) {
            String value = "";
            for (ExcelService.RowData row : sheet.rows()) {
                String candidate = row.values().get(header);
                if (!TextUtils.isBlank(candidate)) {
                    value = candidate.length() > 40 ? candidate.substring(0, 40) : candidate;
                    break;
                }
            }
            sample.put(header, value);
        }
        return sample;
    }

    private String detectRole(List<String> headers) {
        Map<String, String> suggested = catalog.suggest(headers, settings.mergedAliases());
        if (suggested.containsKey("annual_left")) {
            return "annual_balance";
        }
        if (suggested.containsKey("overtime_hours")) {
            return "overtime";
        }
        if (suggested.containsKey("leave_type") || suggested.containsKey("start_time")) {
            boolean target = false;
            for (String header : headers) {
                if (TextUtils.norm(header).contains("免打卡")) {
                    target = true;
                    break;
                }
            }
            return target ? "target_template" : "leave_request";
        }
        return "unknown";
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank() || !ROLE_LABELS.containsKey(role)) {
            return "auto";
        }
        return role;
    }

    private String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase();
    }

    public Optional<FileMeta> find(String fileId) {
        synchronized (this) {
            for (FileMeta file : files) {
                if (file.fileId.equals(fileId)) {
                    return Optional.of(file);
                }
            }
            return Optional.empty();
        }
    }

    public FileMeta require(String fileId) {
        return find(fileId).orElseThrow(() -> new IllegalArgumentException("文件不存在或已清理：" + fileId));
    }

    public Path pathOf(FileMeta meta) {
        Path path = paths.uploadPath(meta.storedName);
        if (!Files.exists(path)) {
            throw new IllegalStateException("文件已经不在 uploads 目录：" + meta.name);
        }
        return path;
    }

    public ExcelService.SheetData readSheet(FileMeta meta) {
        Path path = pathOf(meta);
        Map<String, List<String>> aliases = settings.mergedAliases();
        boolean autoHeader = settings.boolRule("auto_header", true);
        // 表头别名和自动表头开关变了，缓存必须失效，否则规则改了还读到旧表头
        int config = aliases.hashCode() * 31 + Boolean.hashCode(autoHeader);
        long modified = modifiedOf(path);
        CachedSheet hit = sheetCache.get(meta.fileId);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.at() < SHEET_CACHE_TTL_MS
                && hit.modified() == modified && hit.config() == config) {
            return hit.data();
        }
        ExcelService.SheetData data = excel.read(path, catalog, aliases, autoHeader);
        sheetCache.entrySet().removeIf(entry -> now - entry.getValue().at() > SHEET_CACHE_TTL_MS);
        sheetCache.put(meta.fileId, new CachedSheet(now, modified, config, data));
        return data;
    }

    private long modifiedOf(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    public synchronized List<FileMeta> all() {
        return new ArrayList<>(files);
    }

    public int count() {
        return all().size();
    }
}

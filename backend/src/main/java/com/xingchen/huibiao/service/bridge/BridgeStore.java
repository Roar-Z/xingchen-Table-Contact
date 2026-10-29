package com.xingchen.huibiao.service.bridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.model.BridgeDataset;
import com.xingchen.huibiao.util.TextUtils;
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
import java.nio.file.StandardCopyOption;
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

/**
 * 智能体模式的数据集仓库：把上传的 zip / 文件夹摊到磁盘上，元数据和自定义规则各存一个 JSON。
 * 和 files.json 分开，标准模式的源表不会受影响。
 */
@Service
public class BridgeStore {

    private static final Set<String> TABLE_EXT = Set.of(".xlsx", ".xls", ".csv");
    private static final int MAX_FILES = 300;
    /** 最近记录最多返回 100 条，与页面上的清理入口配合 */
    private static final int MAX_RECENT = 100;
    private static final long MAX_ENTRY_BYTES = 40L * 1024 * 1024;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataPaths paths;
    private final ObjectMapper mapper;

    public BridgeStore(DataPaths paths, ObjectMapper mapper) {
        this.paths = paths;
        this.mapper = mapper;
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

    private Path metaFile(String id) {
        return datasetDir(id).resolve("meta.json");
    }

    /** 自定义字段匹配规则：标准字段 ← 各系统里的叫法，跨数据集复用 */
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
            Path temp = metaFile(dataset.id).resolveSibling("meta.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), dataset);
            Files.move(temp, metaFile(dataset.id), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("保存数据集失败：" + e.getMessage(), e);
        }
    }

    public BridgeDataset load(String id) {
        Path meta = metaFile(id);
        if (!Files.exists(meta)) {
            throw new IllegalArgumentException("数据集不存在，可能已被清理，请重新上传");
        }
        try {
            return mapper.readValue(meta.toFile(), BridgeDataset.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取数据集失败：" + e.getMessage(), e);
        }
    }

    /** 最近上传的数据集，给页面做“继续上一单” */
    public List<BridgeDataset> recent() {
        List<BridgeDataset> list = new ArrayList<>();
        Path dir = root().resolve("datasets");
        if (!Files.exists(dir)) {
            return list;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                Path meta = child.resolve("meta.json");
                if (!Files.isDirectory(child) || !Files.exists(meta)) continue;
                try {
                    list.add(mapper.readValue(meta.toFile(), BridgeDataset.class));
                } catch (IOException ignored) {
                    // 半途写坏的文件直接跳过，不影响其他数据集
                }
            }
        } catch (IOException e) {
            return list;
        }
        list.sort(Comparator.comparing((BridgeDataset ds) -> ds.createdAt == null ? "" : ds.createdAt).reversed());
        // 最近记录列表最多返回 100 条，配合前端的滚动与清理入口
        return list.size() > MAX_RECENT ? list.subList(0, MAX_RECENT) : list;
    }

    /**
     * 删除一个数据集：整目录连同源表、方案与产出一起清掉。
     * 编号先校验必须落在 datasets 目录内，避免被拼出越界路径。
     */
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
    }

    /** 批量删除；ids 为空表示清空全部数据集。返回实际删除的个数。 */
    public synchronized int delete(List<String> ids) {
        Path base = root().resolve("datasets").normalize();
        if (!Files.exists(base)) {
            return 0;
        }
        if (ids == null || ids.isEmpty()) {
            int count = 0;
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
            return count;
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

    /**
     * 把上传内容摊到 files/ 下：zip 解压、文件夹里选中的文件逐个复制，只留表格。
     * 返回实际落盘的表格数。
     */
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

    private int unzip(byte[] bytes, Path target) {
        int count = unzip(bytes, target, StandardCharsets.UTF_8);
        if (count == 0) {
            // 国内很多压缩工具用 GBK 存文件名，UTF-8 读不出来时再试一次
            count = unzip(bytes, target, Charset.forName("GBK"));
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
        } catch (IOException e) {
            throw new IllegalStateException("解压压缩包失败：" + e.getMessage(), e);
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

    /** 同名文件加序号，避免后上传的把前面的覆盖掉 */
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

    /* ==================== 自定义匹配规则 ==================== */

    public List<Map<String, String>> rules() {
        Path file = rulesFile();
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(file.toFile(), new TypeReference<>() {
            });
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /** 同一个别名只留一条，重复保存时按最新覆盖 */
    public synchronized List<Map<String, String>> addRule(String standard, String alias) {
        List<Map<String, String>> rules = rules();
        String key = TextUtils.norm(alias);
        rules.removeIf(item -> TextUtils.norm(item.get("alias")).equals(key));
        Map<String, String> rule = new LinkedHashMap<>();
        rule.put("standard", standard);
        rule.put("alias", alias);
        rule.put("at", now());
        rules.add(rule);
        try {
            Files.createDirectories(root());
            mapper.writerWithDefaultPrettyPrinter().writeValue(rulesFile().toFile(), rules);
        } catch (IOException e) {
            throw new IllegalStateException("保存自定义规则失败：" + e.getMessage(), e);
        }
        return rules;
    }

    public synchronized List<Map<String, String>> removeRule(String alias) {
        List<Map<String, String>> rules = rules();
        rules.removeIf(item -> TextUtils.norm(item.get("alias")).equals(TextUtils.norm(alias)));
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(rulesFile().toFile(), rules);
        } catch (IOException e) {
            throw new IllegalStateException("删除自定义规则失败：" + e.getMessage(), e);
        }
        return rules;
    }
}

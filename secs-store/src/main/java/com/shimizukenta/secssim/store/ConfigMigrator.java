package com.shimizukenta.secssim.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.shimizukenta.jsonhub.JsonHub;

/**
 * 一次性迁移（设计 §2.3.3）：work-dir 的 JSON 配置 → config.db。
 *
 * <p><b>复制语义 + 末尾归档</b>：导入全程复制原文件，全部自检通过后才把
 * 原件移入 {@code legacy/}——迁移半途失败时 JSON 原地不动，work-dir 仍可用
 * 2.x 方式启动，下次启动重新迁移。幂等：以正文 SHA-256 判重，中断后续跑。</p>
 *
 * <p>自检项（任一不过整体回滚）：每台设备导入前后正文 SHA-256 相符、
 * legacy_id 无重复、name 在未删除行内唯一、自定义模板正文可解析。
 * 本模块不编译配置（那是 secs-runtime 的职责）；编译不过的配置导入后
 * 会在建会话时报错，与文件时代一致。</p>
 */
public final class ConfigMigrator {

    /** 迁移报告。 */
    public record Report(int devices, int templates, List<String> problems, boolean archived) {
    }

    private final Db db;
    private final ConfigRepository configs;
    private final TemplateRepository templates;

    /** @param db 双库门面 */
    public ConfigMigrator(Db db, ConfigRepository configs, TemplateRepository templates) {
        this.db = db;
        this.configs = configs;
        this.templates = templates;
    }

    /**
     * 判断是否需要迁移：config.db 还没有设备，且 work-dir 下有 *.json。
     *
     * @param workDir 工作目录
     * @return true = 建议执行 {@link #migrate}
     */
    public boolean needed(Path workDir) {
        if (!this.configs.listDevices().isEmpty()) {
            return false;
        }
        if (this.db.meta("config", "migration_source") != null) {
            return false;
        }
        try (Stream<Path> entries = Files.list(workDir)) {
            return entries.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().endsWith(".json"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 执行迁移。
     *
     * @param workDir        工作目录
     * @param builtinTemplates 内置模板种子（id → 正文；只播种缺失的）
     * @return 报告；problems 非空表示自检不过、未归档（原文件原地不动）
     */
    public Report migrate(Path workDir, java.util.Map<String, String> builtinTemplates) {
        List<String> problems = new ArrayList<>();
        Set<String> legacyIds = new HashSet<>();
        Set<String> names = new HashSet<>();
        List<Path> configFiles = new ArrayList<>();
        List<Path> templateFiles = new ArrayList<>();

        try (Stream<Path> entries = Files.list(workDir)) {
            entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(configFiles::add);
        } catch (IOException e) {
            problems.add("读不了工作目录: " + e.getMessage());
            return new Report(0, 0, problems, false);
        }
        Path templateDir = workDir.resolve("templates");
        if (Files.isDirectory(templateDir)) {
            try (Stream<Path> entries = Files.list(templateDir)) {
                entries.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(templateFiles::add);
            } catch (IOException e) {
                problems.add("读不了模板目录: " + e.getMessage());
            }
        }

        /* 1. 内置模板播种（幂等） */
        int seeded = 0;
        for (var entry : builtinTemplates.entrySet()) {
            if (this.templates.seed(entry.getKey(), entry.getKey(), "内置模板", true, entry.getValue())) {
                seeded++;
            }
        }

        /* 2. 自定义模板导入 */
        int templatesImported = 0;
        for (Path file : templateFiles) {
            String id = stem(file);
            try {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                stripBom(body);
                JsonHub.fromJson(body);
                if (this.templates.body(id).isEmpty()) {
                    this.templates.createCustom(id, id, "迁移自 templates/" + file.getFileName(), body);
                }
                templatesImported++;
            } catch (IOException | RuntimeException e) {
                problems.add("模板 " + file.getFileName() + " 无法解析: " + e.getMessage());
            }
        }

        /* 3. 设备导入（复制语义：先入库，自检全过后才归档） */
        List<Path> imported = new ArrayList<>();
        int devices = 0;
        for (Path file : configFiles) {
            String stem = stem(file);
            String legacy = stem;
            try {
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                String body = stripBom(raw);
                JsonHub.fromJson(body);
                if (legacyIds.contains(legacy)) {
                    problems.add("legacy id 重复: " + legacy);
                    continue;
                }
                if (names.contains(stem)) {
                    problems.add("设备名重复: " + stem);
                    continue;
                }
                /* 幂等：同名 legacy 已在库且正文一致则跳过（中断续跑） */
                var existing = this.configs.findDevice(legacy);
                if (existing.isPresent()) {
                    var doc = this.configs.readDocument(legacy);
                    if (doc.isPresent() && doc.get().sha256().equals(ContentHash.sha256Hex(body))) {
                        legacyIds.add(legacy);
                        names.add(stem);
                        imported.add(file);
                        devices++;
                        continue;
                    }
                    problems.add("设备 " + legacy + " 已存在但正文不一致，跳过导入");
                    continue;
                }
                this.configs.createDevice(stem, legacy, templateMarker(body), body, true, "migrate");
                imported.add(file);
                legacyIds.add(legacy);
                names.add(stem);
                devices++;
            } catch (IOException | RuntimeException e) {
                problems.add("配置 " + file.getFileName() + " 无法导入: " + e.getMessage());
            }
        }

        /* 4. 自检：逐台比对 SHA-256（复制语义的最终裁判） */
        for (Path file : imported) {
            String legacy = stem(file);
            try {
                String body = stripBom(Files.readString(file, StandardCharsets.UTF_8));
                var doc = this.configs.readDocument(legacy);
                if (doc.isEmpty() || !doc.get().sha256().equals(ContentHash.sha256Hex(body))) {
                    problems.add("自检失败: " + legacy + " 正文与原文件不一致");
                }
            } catch (IOException e) {
                problems.add("自检读不到原文件: " + file.getFileName());
            }
        }
        boolean archived = false;
        if (problems.isEmpty() && !imported.isEmpty()) {
            /* 5. 全部通过才归档：move 进 legacy/（绝不自动删除） */
            try {
                Path legacyDir = workDir.resolve("legacy");
                Files.createDirectories(legacyDir);
                for (Path file : imported) {
                    move(file, legacyDir.resolve(file.getFileName()));
                }
                for (Path file : templateFiles) {
                    if (Files.exists(file)) {
                        move(file, legacyDir.resolve(file.getFileName()));
                    }
                }
                this.db.putMeta("config", "migration_source", "files@"
                        + java.time.LocalDate.now());
                archived = true;
            } catch (IOException e) {
                problems.add("归档失败（数据库已导入，原文件保留原地）: " + e.getMessage());
            }
        }
        return new Report(devices, seeded + templatesImported, problems, archived);
    }

    /**
     * 全量导出（回滚通道）：每台设备写 {@code <legacy_id>.json}（无 legacy 用 name）。
     *
     * @param dir 目标目录（自动创建；已存在的同名文件覆盖）
     * @return 导出的设备数
     */
    public int exportTo(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new StoreException("导出目录不可用: " + dir + ": " + e.getMessage(), e);
        }
        int count = 0;
        for (ConfigRepository.DeviceView device : this.configs.listDevices()) {
            var doc = this.configs.readDocument(device.legacyId() != null ? device.legacyId() : device.id());
            if (doc.isEmpty()) {
                continue;
            }
            String fileName = (device.legacyId() != null ? device.legacyId() : device.name()) + ".json";
            try {
                Path target = dir.resolve(fileName);
                Path tmp = dir.resolve(fileName + ".tmp");
                Files.writeString(tmp, doc.get().body(), StandardCharsets.UTF_8);
                move(tmp, target);
                count++;
            } catch (IOException e) {
                throw new StoreException("导出 " + fileName + " 失败: " + e.getMessage(), e);
            }
        }
        return count;
    }

    /* ---------- 工具 ---------- */

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    /** 顶层 template 标记（来源模板归属，仅分组用）。 */
    private static String templateMarker(String body) {
        try {
            JsonHub root = JsonHub.fromJson(body);
            if (root.isObject() && root.get("template") != null) {
                return root.get("template").optionalString().orElse(null);
            }
        } catch (RuntimeException e) {
            /* 解析失败留给调用方的统一报错 */
        }
        return null;
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;
import com.shimizukenta.jsonhub.JsonString;
import com.shimizukenta.secssim.api.http.ConfigOutcome;
import com.shimizukenta.secssim.api.http.ConfigResult;
import com.shimizukenta.secssim.app.config.ConfigException;
import com.shimizukenta.secssim.app.config.SessionConfig;
import com.shimizukenta.secssim.app.config.SessionConfigJson;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.domain.EquipmentConstantDef;

/**
 * 配置文件的读取、校验与保存。
 *
 * <p><b>编辑器操作的是原始 JSON 文本</b>，不经过领域模型往返：
 * {@link SessionConfigJson#toJson} 只认模型里有的字段，手写的额外键会被静默丢掉。
 * 一个保存动作悄悄改掉用户的配置，比报错糟糕得多。
 * 模型往返仍用于"生成一份新配置"。
 * </p>
 *
 * <p><b>先校验再落盘</b>：存一份编译不过的配置，等于给下一次启动埋雷。
 * </p>
 *
 * <p>文件名的约束与 {@link SessionLauncher} 一致：只收文件名，不收路径。
 * </p>
 */
public final class ConfigStore {

    /** 配置文件名后缀。 */
    private static final String CONFIG_SUFFIX = ".json";

    /**
     * 进程内的「读-改-写」串行锁。
     *
     * <p>同一份配置有两条写入路径：界面保存（{@link #save}）与主机 S2F15 常量落盘
     * （{@link #writeConstants}，由协议线程直接调用）。两者都是「读原文 → 改一段 →
     * 原子替换」，不加锁就会互相覆盖：用户刚存的其他段落被 S2F15 写回旧版本，
     * 或者反过来。跨进程的外部修改由 mtime 乐观检查兜底。</p>
     */
    private static final java.util.concurrent.locks.ReentrantLock FILE_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    private final Path workDir;

    /**
     * 创建配置仓库。
     *
     * @param workDir 配置目录
     */
    public ConfigStore(Path workDir) {
        this.workDir = workDir;
    }

    /** 配置目录。 */
    public Path workDir() {
        return this.workDir;
    }

    /**
     * 目录下的配置文件（按名称排序）。
     *
     * <p>只收<b>普通文件</b>：一个恰好叫 {@code backup.json} 的子目录不是配置，
     * 把它列进编辑器，用户点进去才发现打不开。
     * </p>
     */
    public List<Entry> list() {
        if (!Files.isDirectory(this.workDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(this.workDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(CONFIG_SUFFIX))
                    .map(this::describe)
                    .sorted(Comparator.comparing(Entry::name))
                    .toList();
        } catch (IOException e) {
            Log.error("读取配置目录失败: " + this.workDir, e);
            return List.of();
        }
    }

    /**
     * 目录内容的 JSON 数组。
     *
     * <p>顺带带上大小与修改时间：编辑器的"这份配置被谁改过"一栏，
     * 光看文件名根本无从判断。
     * </p>
     *
     * @return JSON 数组文本
     */
    public String listJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Entry entry : this.list()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"name\":\"").append(escape(entry.name()))
                    .append("\",\"sizeBytes\":").append(entry.sizeBytes())
                    .append(",\"modifiedMs\":").append(entry.modifiedMs())
                    .append(",\"template\":\"").append(escape(entry.template()))
                    .append("\"}");
        }
        return sb.append(']').toString();
    }

    /**
     * 一次读取配置正文与修改时间（编辑器保存用的并发基准）。
     *
     * <p>分两次取会出现「旧正文 + 新 mtime」：保存时被判为"没人改过"，
     * 于是绕过 If-Match 覆盖别人的修改。这里在锁内读，并用前后 mtime 比对重试，
     * 同时挡住进程内的写（save / S2F15）与进程外的编辑。</p>
     *
     * @param name 文件名
     * @return 快照；文件不存在或读失败返回 {@code null}
     */
    public com.shimizukenta.secssim.api.http.ApiContext.ConfigSnapshot readSnapshot(String name) {
        Path file;
        try {
            file = this.workDir.resolve(requireFileName(name, "$.name"));
        } catch (ConfigException e) {
            return null;
        }

        FILE_LOCK.lock();
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                long before = Files.getLastModifiedTime(file).toMillis();
                String text = Files.readString(file, StandardCharsets.UTF_8);
                long after = Files.getLastModifiedTime(file).toMillis();
                if (before == after) {
                    return new com.shimizukenta.secssim.api.http.ApiContext.ConfigSnapshot(text, after);
                }
            }
            Log.warn("配置在读期间连续变化，返回最新一次内容: " + file.getFileName());
            return new com.shimizukenta.secssim.api.http.ApiContext.ConfigSnapshot(
                    Files.readString(file, StandardCharsets.UTF_8),
                    Files.getLastModifiedTime(file).toMillis());
        } catch (IOException e) {
            Log.error("读取配置失败: " + file.getFileName(), e);
            return null;
        } finally {
            FILE_LOCK.unlock();
        }
    }

    /**
     * 取一份配置的最后修改时间（epoch 毫秒）。
     *
     * @param name 文件名
     * @return 修改时间；文件不存在、名字非法或取不到时返回 {@code null}
     */
    public Long modifiedMs(String name) {
        Path file;
        try {
            file = this.workDir.resolve(requireFileName(name, "$.name"));
        } catch (ConfigException e) {
            return null;
        }
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return null;
        }
    }

    /** 取一个配置文件的大小与修改时间；取不到就报 0/0，列表不该因为一个坏文件整列崩掉。 */
    private Entry describe(String name) {
        Path file = this.workDir.resolve(name);
        if (!Files.isRegularFile(file)) {
            return new Entry(name, 0L, 0L, "");
        }
        String template = "";
        try {
            JsonHub root = JsonHub.fromJson(Files.readString(file, StandardCharsets.UTF_8));
            if (root != null && root.isObject()) {
                JsonHub marker = root.get("template");
                if (marker != null) {
                    template = marker.optionalString().orElse("");
                }
            }
        } catch (RuntimeException | IOException e) {
            /* 坏配置仍要出现在列表里，只是没有模板归属 */
        }
        try {
            return new Entry(name, Files.size(file), Files.getLastModifiedTime(file).toMillis(), template);
        } catch (IOException e) {
            return new Entry(name, 0L, 0L, template);
        }
    }

    /**
     * 一个配置文件的元信息。
     *
     * @param name       文件名
     * @param sizeBytes  字节数
     * @param modifiedMs 最后修改时间（epoch 毫秒）
     */
    public record Entry(String name, long sizeBytes, long modifiedMs, String template) {
    }

    /**
     * 读原始文本。
     *
     * @param name 文件名
     * @return 原文
     * @throws ConfigException 名字非法或文件不存在
     * @throws IOException     读失败
     */
    public String read(String name) throws ConfigException, IOException {
        return Files.readString(this.resolve(name), StandardCharsets.UTF_8);
    }

    /**
     * 校验一段配置文本（不落盘）。
     *
     * <p>判据要和 {@code SessionLauncher.prepare} 一致：解析、编译设备模型、
     * 再把模板图建一遍。只编译设备是不够的——
     * 模板表达式写错一样起不来，而用户是从编辑器里按下"校验"的，
     * 他要的就是"这份配置能不能起得来"这句话的答案。
     * </p>
     *
     * @param text JSON 文本
     * @return 问题列表；空表示合法
     */
    public static List<String> validate(String text) {
        SessionConfig config;
        try {
            config = SessionConfigJson.parse(text);
        } catch (ConfigException e) {
            return List.of(e.getMessage());
        } catch (RuntimeException e) {
            return List.of("解析失败：" + e);
        }

        CompilationResult compiled;
        try {
            compiled = config.compile();
        } catch (RuntimeException e) {
            return List.of("编译失败：" + e);
        }
        if (!compiled.ok()) {
            return compiled.errors().stream().map(Object::toString).toList();
        }

        try {
            if (config.hasCustomValues()) {
                /* 模板 + SVID 直配(v6)统一在模板图里过单写者/环/未知引用 */
                config.buildValues(compiled.equipment());
            }
        } catch (RuntimeException e) {
            return List.of("值来源配置错误：" + e);
        }

        return List.of();
    }

    /**
     * 新建一份配置，内容是能直接编译的骨架。
     *
     * <p>骨架由后端生成而不是前端各抄一份：只有一份判据，
     * 不会出现"界面上给的模板起不来"。
     * </p>
     *
     * @param name 文件名
     * @return 结果
     */
    public ConfigResult create(String name) {
        return this.create(name, DeviceTemplates.BARE_ID);
    }

    /**
     * 按内置设备模板新建一份配置。
     *
     * <p>模板目录见 {@link DeviceTemplates#catalog()}。模板生成后仍要过一遍
     * {@link #validate}：模板是代码里的字符串，改了模板忘了跟着改设备模型，
     * 用户就会拿到一份起不来的起点——校验是同一道门，不会漏。</p>
     *
     * @param name       文件名
     * @param templateId 模板标识；未知模板按非法请求拒绝，不静默退回骨架
     * @return 结果
     */
    public ConfigResult create(String name, String templateId) {
        Path file;
        try {
            file = this.workDir.resolve(requireFileName(name, "$.name"));
        } catch (ConfigException e) {
            return ConfigResult.notFound(e.getMessage());
        }

        if (Files.exists(file)) {
            /* 新建不该悄悄覆盖别人的配置：让用户直接打开那份改 */
            return ConfigResult.conflict("已经有同名的配置了: " + file.getFileName());
        }

        String text = DeviceTemplates.json(templateId, file.getFileName().toString()).orElse(null);
        if (text == null) {
            return ConfigResult.invalid(List.of(
                    "未知模板: " + templateId + "（可用: " + DeviceTemplates.ids() + "）"));
        }
        return this.createFromTemplateText(name, text, templateId);
    }

    /**
     * 用任意模板文本新建配置：先补顶层 {@code template} 标记，再校验、落盘。
     *
     * @param name       设备文件名
     * @param text       模板配置原文
     * @param templateId 模板 id（写进配置供侧栏分组）
     * @return 结果
     */
    public ConfigResult createFromText(String name, String text, String templateId) {
        Path file;
        try {
            file = this.workDir.resolve(requireFileName(name, "$.name"));
        } catch (ConfigException e) {
            return ConfigResult.notFound(e.getMessage());
        }
        if (Files.exists(file)) {
            return ConfigResult.conflict("已经有同名的配置了: " + file.getFileName());
        }
        String marked = TemplateStore.withTemplate(text, templateId);
        List<String> problems = validate(marked);
        if (!problems.isEmpty()) {
            Log.error("配置骨架生成失败: " + file.getFileName(), null);
            return ConfigResult.invalid(problems);
        }
        try {
            writeAtomically(file, marked);
            Log.info("已新建配置: " + file.getFileName());
            return ConfigResult.ok();
        } catch (IOException e) {
            Log.error("新建配置失败: " + file.getFileName(), e);
            return ConfigResult.failed("写盘失败：" + e.getMessage());
        }
    }

    /**
     * 用自定义模板新建设备：把 session.id / equipment.name 改成文件名主名，保证设备名与文件名一致。
     */
    public ConfigResult createFromTemplateText(String name, String text, String templateId) {
        String base = name.trim();
        if (base.toLowerCase(java.util.Locale.ROOT).endsWith(CONFIG_SUFFIX)) {
            base = base.substring(0, base.length() - CONFIG_SUFFIX.length());
        }
        try {
            JsonHub root = JsonHub.fromJson(text);
            if (root.isObject()) {
                JsonHubBuilder b = JsonHub.getBuilder();
                List<JsonObjectPair> pairs = new ArrayList<>();
                for (JsonString key : root.keySet()) {
                    String field = key.unescaped();
                    if ("session".equals(field) && root.get(field).isObject()) {
                        JsonHub session = root.get(field);
                        List<JsonObjectPair> sessionPairs = new ArrayList<>();
                        for (JsonString sessionKey : session.keySet()) {
                            String nameKey = sessionKey.unescaped();
                            sessionPairs.add("id".equals(nameKey)
                                    ? b.pair("id", base)
                                    : b.pair(nameKey, session.get(nameKey)));
                        }
                        pairs.add(b.pair("session", b.object(sessionPairs)));
                    } else if ("equipment".equals(field) && root.get(field).isObject()) {
                        JsonHub equipment = root.get(field);
                        List<JsonObjectPair> equipmentPairs = new ArrayList<>();
                        for (JsonString equipmentKey : equipment.keySet()) {
                            String nameKey = equipmentKey.unescaped();
                            equipmentPairs.add("name".equals(nameKey)
                                    ? b.pair("name", base)
                                    : b.pair(nameKey, equipment.get(nameKey)));
                        }
                        pairs.add(b.pair("equipment", b.object(equipmentPairs)));
                    } else {
                        pairs.add(b.pair(field, root.get(field)));
                    }
                }
                text = b.object(pairs).toJson();
            }
        } catch (RuntimeException e) {
            /* 原样交给校验报错 */
        }
        return this.createFromText(name, text, templateId);
    }

    /**
     * 保存配置：先校验，通过后原子写盘。
     *
     * <p>文件可以<b>原本不存在</b>：模板编辑器要能从零建一份配置，
     * 否则"新建"就得先在资源管理器里手搓一个 json。
     * 名字的约束不变——仍是 {@code --work-dir} 下的一个文件名。
     * </p>
     *
     * @param name 文件名
     * @param text JSON 文本
     * @return 校验/落盘结果，问题清单随结果带回
     */
    public ConfigResult save(String name, String text) {
        return this.save(name, text, null);
    }

    /**
     * 保存配置（带并发保护）。
     *
     * <p>{@code expectedModifiedMs} 非空时（界面从 GET 响应里拿到的修改时间，
     * 经 If-Match 头传回），若文件在这期间被别的窗口/进程改过，就拒绝覆盖并回
     * CONFLICT——两个窗口同时编辑同一份配置时，后保存的一方不该悄悄抹掉先保存的。
     * </p>
     *
     * @param name               文件名
     * @param text               JSON 文本
     * @param expectedModifiedMs 期望的最后修改时间；null 表示不检查
     * @return 校验/落盘结果，问题清单随结果带回
     */
    public ConfigResult save(String name, String text, Long expectedModifiedMs) {
        Path file;
        try {
            file = this.workDir.resolve(requireFileName(name, "$.name"));
        } catch (ConfigException e) {
            return ConfigResult.notFound(e.getMessage());
        }

        List<String> problems = validate(text);
        if (!problems.isEmpty()) {
            /* 存一份编译不过的配置，等于给下一次启动埋雷 */
            return ConfigResult.invalid(problems);
        }

        FILE_LOCK.lock();
        try {
            if (expectedModifiedMs != null) {
                Long current = this.modifiedMs(name);
                if (current != null && !current.equals(expectedModifiedMs)) {
                    return ConfigResult.conflict("配置在编辑期间被其他操作修改过，请重新载入后再保存");
                }
            }

            writeAtomically(file, text);
            Log.info("配置已保存: " + file.getFileName());
            return ConfigResult.ok();
        } catch (IOException e) {
            Log.error("配置保存失败: " + file.getFileName(), e);
            return ConfigResult.failed("写盘失败：" + e.getMessage());
        } finally {
            FILE_LOCK.unlock();
        }
    }

    /**
     * 把设备常量写回配置文件。
     *
     * <p>用于 S2F15（主机写 ECV）：主机改的是设备的“调机参数”，现场预期是
     * <b>重启/重载后仍然保留</b>，所以必须落盘，而不是只留在内存里。</p>
     *
     * <p>做法是「解析原文 → 只替换 equipment.constants 节点 → 重新序列化 → 原子替换」：
     * 其它段（session / 单元 / 模板 / 编排）、未知键、schemaVersion 全部原样保留，
     * 不会被模型往返悄悄改掉。唯一的变化是缩进/空行会统一成 2 空格——
     * 这是保住“其余内容一个键都不丢”必须付的小代价。</p>
     *
     * @param file      配置文件
     * @param constants ECID → 常量（调用方传入的是快照）
     * @throws IOException 读不了、解析不了或写不了
     */
    public static void writeConstants(Path file, Map<Integer, EquipmentConstantDef> constants) throws IOException {
        FILE_LOCK.lock();
        try {
            writeConstantsLocked(file, constants);
        } finally {
            FILE_LOCK.unlock();
        }
    }

    /**
     * 常量落盘的实际执行体（调用方已持有 {@link #FILE_LOCK}）。
     *
     * <p>除了进程内互斥，这里还用 mtime 做一次乐观检查：读原文前后修改时间不一致，
     * 说明有别的东西（另一个进程、手工编辑）动过它，此时<b>不覆盖</b>并报错，
     * 让主机侧按 S2F16=2（不落盘）处理。</p>
     */
    private static void writeConstantsLocked(Path file, Map<Integer, EquipmentConstantDef> constants) throws IOException {
        final long modifiedBefore;
        try {
            modifiedBefore = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new IOException("读不了配置 " + file.getFileName() + ": " + e.getMessage(), e);
        }

        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("读不了配置 " + file.getFileName() + ": " + e.getMessage(), e);
        }

        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        JsonHub root;
        try {
            root = JsonHub.fromJson(text);
        } catch (RuntimeException e) {
            /* 解析失败就不覆盖：宁可写失败，也不要把原文弄丢 */
            throw new IOException("配置无法解析，拒绝覆盖 " + file.getFileName() + ": " + e.getMessage(), e);
        }

        if (!root.isObject() || !root.containsKey("equipment") || !root.get("equipment").isObject()) {
            throw new IOException("配置没有 equipment 段，拒绝覆盖 " + file.getFileName());
        }

        JsonHubBuilder b = JsonHub.getBuilder();
        JsonHub equipment = root.get("equipment");
        List<JsonObjectPair> equipmentPairs = new ArrayList<>();
        boolean replaced = false;

        for (JsonString key : equipment.keySet()) {
            String name = key.unescaped();
            if ("constants".equals(name)) {
                equipmentPairs.add(b.pair(name, SessionConfigJson.constantsJson(constants.values(), b)));
                replaced = true;
            } else {
                equipmentPairs.add(b.pair(name, equipment.get(name)));
            }
        }
        if (!replaced) {
            equipmentPairs.add(b.pair("constants", SessionConfigJson.constantsJson(constants.values(), b)));
        }

        JsonHub newEquipment = b.object(equipmentPairs);
        List<JsonObjectPair> rootPairs = new ArrayList<>();
        for (JsonString key : root.keySet()) {
            String name = key.unescaped();
            rootPairs.add(b.pair(name, "equipment".equals(name) ? newEquipment : root.get(name)));
        }

        if (Files.getLastModifiedTime(file).toMillis() != modifiedBefore) {
            throw new IOException("配置文件在写入期间被其他操作修改，已放弃覆盖: " + file.getFileName());
        }

        writeAtomically(file, b.object(rootPairs).prettyPrint());
    }

    /**
     * 原子写盘：先写临时文件再整体替换。
     *
     * <p>直接 {@code writeString} 到目标文件的话，中途崩掉会留下半截 JSON——
     * 而这份配置正是设备启动时要读的东西。
     * </p>
     */
    private static void writeAtomically(Path target, String text) throws IOException {
        Path dir = target.getParent();
        if (dir == null) {
            throw new IOException("目标没有父目录: " + target);
        }
        /* 配置目录可能还没建出来：先建再写，免得"新建"因为目录不存在而失败 */
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, ".secs-config-", ".tmp");

        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                /* 某些文件系统（同目录跨卷、网络盘）不支持原子替换；
                   退化成普通替换：仍然比直接写目标文件安全，因为临时文件是完整的 */
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 校验配置文件名，只允许文件名。
     *
     * <p>{@link SessionLauncher} 建会话时也要过这一关：两处各写一份，
     * 迟早会出现"接口能读、命令行读不了"这种只在其中一条路径上复现的问题。
     * </p>
     *
     * @param name  请求给的名字
     * @param field 出错时要指出的字段路径
     * @return 去掉首尾空白后的文件名
     * @throws ConfigException 名字为空、后缀不对或带路径
     */
    static String requireFileName(String name, String field) throws ConfigException {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ConfigException(field, "不能为空");
        }
        if (!trimmed.endsWith(CONFIG_SUFFIX)) {
            throw new ConfigException(field, "必须是 " + CONFIG_SUFFIX + " 文件");
        }
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..") || trimmed.contains(":")) {
            throw new ConfigException(field, "只能是 --work-dir 下的文件名，不能带路径");
        }

        try {
            Path.of(trimmed);
        } catch (InvalidPathException e) {
            throw new ConfigException(field, "不是合法的文件名");
        }
        return trimmed;
    }

    /**
     * 校验名字并定位文件。
     *
     * <p>只允许文件名：挡住 {@code ../}、分隔符与盘符。
     * </p>
     */
    Path resolve(String name) throws ConfigException {
        String trimmed = requireFileName(name, "$.name");
        Path file = this.workDir.resolve(trimmed);
        if (!Files.isRegularFile(file)) {
            throw new ConfigException("$.name", "配置不存在: " + trimmed);
        }
        return file;
    }

    /** JSON 字符串转义；只处理会破坏 JSON 结构的字符，中文原样保留。 */
    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}

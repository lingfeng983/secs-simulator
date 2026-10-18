package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secssim.app.config.ConfigException;

/**
 * API 访问控制：账号集合 + 口令比对。
 *
 * <p>两种来源，语义完全一致：
 * </p>
 * <ul>
 *   <li>{@link #singleToken(String)}——一个 {@link ApiRole#ADMIN} 账号，
 *       就是原来的单 token 行为，现场脚本与前端一行都不用改；</li>
 *   <li>{@link #load(Path)}——多账号文件，按 {@link ApiRole} 分级。</li>
 * </ul>
 *
 * <p>比对口令时<b>遍历完所有账号才返回</b>，不提前 break：
 * 否则"命中第 1 个"和"命中第 5 个"的响应时间不同，
 * 攻击者能用计时侧信道把口令猜到最后一个字符。
 * </p>
 */
public final class AccessControl {

    /** 账号文件里的根键名。 */
    private static final String KEY_USERS = "users";

    private final List<ApiAccount> accounts;

    private AccessControl(List<ApiAccount> accounts) {
        this.accounts = List.copyOf(accounts);
    }

    /**
     * 单令牌模式：整个 API 对一个口令开放，且口令持有者是管理员。
     *
     * @param token 口令
     * @return 访问控制
     */
    public static AccessControl singleToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        return new AccessControl(List.of(new ApiAccount("token", ApiRole.ADMIN, token)));
    }

    /**
     * 由账号列表创建。
     *
     * @param accounts 账号
     * @return 访问控制
     */
    public static AccessControl of(Collection<ApiAccount> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            /* 空账号表等于没有一扇能开的锁：宁可启动失败，也不要静默变成"无鉴权" */
            throw new IllegalArgumentException("至少需要一个账号");
        }

        Set<String> names = new HashSet<>();
        Set<String> secrets = new HashSet<>();
        for (ApiAccount account : accounts) {
            if (!names.add(account.name())) {
                throw new IllegalArgumentException("账号名重复: " + account.name());
            }
            if (!secrets.add(account.secret())) {
                /* 同一个口令配两个账号名，鉴权结果取决于遍历顺序，属于配置错误 */
                throw new IllegalArgumentException("口令重复: " + account.name());
            }
        }
        return new AccessControl(new ArrayList<>(accounts));
    }

    /**
     * 读取账号文件。
     *
     * @param file JSON 文件
     * @return 访问控制
     * @throws IOException 读失败
     * @throws ConfigException 文件格式错误（带字段路径）
     */
    public static AccessControl load(Path file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("账号文件路径为空");
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        /* Windows 上的编辑器/PowerShell 常给 UTF-8 加 BOM，不剥会让根节点解析直接失败 */
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return parse(text);
    }

    /**
     * 解析账号 JSON。
     *
     * <p>形如：</p>
     * <pre>{@code
     * {"users": [
     *   {"name": "guest",  "role": "viewer",   "secret": "..."},
     *   {"name": "line1",  "role": "operator", "secret": "..."},
     *   {"name": "owner",  "role": "admin",    "secret": "..."}
     * ]}
     * }</pre>
     *
     * @param json JSON 文本
     * @return 访问控制
     * @throws ConfigException 格式或字段错误（带字段路径）
     */
    public static AccessControl parse(String json) {
        JsonHub root;
        try {
            root = JsonHub.fromJson(json);
        } catch (RuntimeException e) {
            throw new ConfigException("$", "不是合法 JSON：" + e.getMessage());
        }

        if (!root.isObject()) {
            throw new ConfigException("$", "应为对象");
        }
        if (!root.containsKey(KEY_USERS)) {
            throw new ConfigException("$.users", "缺少必填数组");
        }

        JsonHub users = root.get(KEY_USERS);
        if (!users.isArray()) {
            throw new ConfigException("$.users", "应为数组");
        }
        if (users.length() == 0) {
            throw new ConfigException("$.users", "至少需要一个账号，否则等于没有鉴权");
        }

        List<ApiAccount> accounts = new ArrayList<>(users.length());
        for (int i = 0; i < users.length(); i++) {
            String path = "$.users[" + i + "]";
            JsonHub item = users.get(i);
            if (!item.isObject()) {
                throw new ConfigException(path, "应为对象");
            }

            /* 空白单独判：ApiAccount 抛的是裸 IllegalArgumentException，没有字段路径 */
            String name = requireNonBlank(item, "name", path);
            String secret = requireNonBlank(item, "secret", path);

            accounts.add(new ApiAccount(name, parseRole(item, path), secret));
        }

        try {
            return of(accounts);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("$.users", e.getMessage());
        }
    }

    /**
     * 比对口令。
     *
     * @param secret 调用方出示的口令
     * @return 命中的账号；无任何账号匹配时返回 {@code null}
     */
    public Principal authenticate(String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }

        byte[] probe = secret.getBytes(StandardCharsets.UTF_8);
        Principal matched = null;
        for (ApiAccount account : this.accounts) {
            boolean hit = MessageDigest.isEqual(probe, account.secret().getBytes(StandardCharsets.UTF_8));
            if (hit) {
                matched = new Principal(account.name(), account.role());
            }
            /* 不 break：让所有账号都比一遍，响应时间与"命中了第几个"无关 */
        }
        return matched;
    }

    /**
     * 账号数量（诊断用）。
     *
     * @return 数量
     */
    public int size() {
        return this.accounts.size();
    }

    private static String requireString(JsonHub item, String key, String path) {
        if (!item.containsKey(key)) {
            throw new ConfigException(path + "." + key, "缺少必填字段");
        }
        return item.get(key).optionalString()
                .orElseThrow(() -> new ConfigException(path + "." + key, "应为字符串"));
    }

    /** 必填、非空白字符串。空口令会让"没带凭据"和"带了个空口令"变得无法区分。 */
    private static String requireNonBlank(JsonHub item, String key, String path) {
        String value = requireString(item, key, path);
        if (value.isBlank()) {
            throw new ConfigException(path + "." + key,
                    "不能为空" + ("secret".equals(key) ? "（空口令等于没有口令）" : ""));
        }
        return value;
    }

    private static ApiRole parseRole(JsonHub item, String path) {
        String raw = requireString(item, "role", path);
        try {
            return ApiRole.fromId(raw);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(path + ".role", e.getMessage()
                    + "（可选: viewer/operator/admin）");
        }
    }

    /**
     * 鉴权通过的调用方。
     *
     * @param name 账号名
     * @param role 角色
     */
    public record Principal(String name, ApiRole role) {
    }
}

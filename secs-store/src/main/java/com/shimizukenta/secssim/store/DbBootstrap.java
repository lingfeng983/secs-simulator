package com.shimizukenta.secssim.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * 存储层启动准备：把 sqlite-jdbc 的平台本地库释放到 {@code <work-dir>/native/}。
 *
 * <p>native-image 里 classpath 资源无法被 JNI 直接 dlopen（M0 尖峰的结论），
 * 所以在打开数据库之前把库落盘，并设 {@code org.sqlite.lib.path} /
 * {@code org.sqlite.lib.name}，让 sqlite-jdbc 直接 {@code System.load} 这份副本，
 * 不依赖运行时自解压。work-dir 只读 / 网络盘 / 杀软拦截都会在这一步以
 * {@link IOException} 显式失败——提示写在消息里，不要让它变成无头的 UnsatisfiedLinkError。</p>
 */
public final class DbBootstrap {

    private DbBootstrap() {
    }

    /**
     * 确保本地库已释放并注册加载路径（幂等：已存在且非空则跳过复制）。
     *
     * @param workDir 工作目录
     * @throws IOException 资源缺失或写盘失败
     */
    public static void ensureNativeLibrary(Path workDir) throws IOException {
        String resource = nativeResource();
        Path dir = workDir.resolve("native");
        Files.createDirectories(dir);
        Path target = dir.resolve(Path.of(resource).getFileName());
        if (!Files.isRegularFile(target) || Files.size(target) == 0) {
            try (InputStream in = DbBootstrap.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("classpath 里没有 " + resource
                            + "：检查 reachability-metadata.json 的 resources 段与 -H:IncludeResources");
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        System.setProperty("org.sqlite.lib.path", dir.toAbsolutePath().toString());
        System.setProperty("org.sqlite.lib.name", target.getFileName().toString());
    }

    /** 当前平台的 sqlite-jdbc 本地库 classpath 路径（org/sqlite/native/&lt;OS&gt;/&lt;arch&gt;/…）。 */
    static String nativeResource() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String dir;
        if (os.contains("win")) {
            dir = "Windows";
        } else if (os.contains("linux")) {
            dir = "Linux";
        } else if (os.contains("mac")) {
            dir = "Mac";
        } else {
            throw new IllegalStateException("不支持的操作系统: " + os);
        }
        String archDir = arch.contains("aarch64") || arch.contains("arm") ? "aarch64" : "x86_64";
        String file;
        if (dir.equals("Windows")) {
            file = "sqlitejdbc.dll";
        } else if (dir.equals("Mac")) {
            file = "libsqlitejdbc.jnilib";
        } else {
            file = "libsqlitejdbc.so";
        }
        return "org/sqlite/native/" + dir + "/" + archDir + "/" + file;
    }
}

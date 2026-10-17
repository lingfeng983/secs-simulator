package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * 从 PKCS#12 keystore 加载服务端 TLS 上下文。
 *
 * <p>只做一件事：把运维给的 keystore 变成 {@link SSLContext}。
 * 不生成证书、不信任所有客户端——证书生命周期属于部署，不属于模拟器。</p>
 */
public final class TlsSupport {

    private TlsSupport() {
    }

    /**
     * 加载 keystore。
     *
     * @param keystore PKCS#12 文件
     * @param password keystore/私钥密码
     * @return TLS 上下文
     * @throws IOException             读取失败
     * @throws GeneralSecurityException keystore 格式或密码错误
     */
    public static SSLContext load(Path keystore, String password)
            throws IOException, GeneralSecurityException {
        if (keystore == null) {
            throw new IllegalArgumentException("keystore must not be null");
        }
        if (!Files.isRegularFile(keystore)) {
            throw new IOException("TLS keystore 不存在: " + keystore);
        }

        char[] secret = password == null ? new char[0] : password.toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");

        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, secret);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, secret);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        return context;
    }
}

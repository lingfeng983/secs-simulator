package com.shimizukenta.secssim.cli;

import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.runtime.Bootstrap;

/**
 * 进程入口。
 *
 * <p>只做三件事：调用 {@link Bootstrap#run} 装配并阻塞、把异常收敛成退出码、
 * 用 {@code System.exit} 结束进程。装配逻辑全部在 {@code secs-runtime}，
 * 这里保持"薄"是为了让运行时模块可以当作库被测试或其他宿主直接启动。
 * </p>
 *
 * <p>退出码约定：0 正常停机；1 启动失败；2 参数 / TLS 错误；3 配置错误。</p>
 */
public final class CliMain {

    private CliMain() {
    }

    /**
     * 进程入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        int code;
        try {
            code = Bootstrap.run(args);
        } catch (Exception e) {
            /* 单行摘要给桌面壳看；完整栈只在需要时输出，避免刷屏 */
            Log.error("启动失败", e);
            if (System.getenv("SECS_SIM_DEBUG") != null) {
                e.printStackTrace();
            }
            code = 1;
        }
        System.exit(code);
    }
}

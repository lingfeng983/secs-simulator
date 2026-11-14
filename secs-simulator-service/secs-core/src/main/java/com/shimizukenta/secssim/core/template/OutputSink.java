package com.shimizukenta.secssim.core.template;

/**
 * 模板把算出来的值写到这里。
 */
@FunctionalInterface
public interface OutputSink {

    /**
     * 写入一个输出。
     *
     * @param output 输出名
     * @param value  值
     */
    void put(String output, double value);
}

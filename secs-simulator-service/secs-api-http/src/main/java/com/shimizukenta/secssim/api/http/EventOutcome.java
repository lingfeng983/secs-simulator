package com.shimizukenta.secssim.api.http;

/**
 * 事件编排操作结果。
 *
 * <p>会话不存在与计划不存在都返回 404，但错误码要分开：
 * 现场需要知道是"会话名字打错了"还是"计划名字打错了"。</p>
 */
public enum EventOutcome {
    /** 成功。 */
    OK,
    /** 会话不存在。 */
    UNKNOWN_SESSION,
    /** 会话存在，但没有这个计划。 */
    UNKNOWN_PLAN
}

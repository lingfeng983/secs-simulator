/**
 * 事件总线：把会话里发生的报文广播给观察者（HTTP SSE、日志、测试）。
 *
 * <p>放在应用层是为了让"会话"与"传输"互不认识：会话只管发布事件，
 * SSE 适配器只管订阅，谁也不需要知道对方的存在。
 * </p>
 */
package com.shimizukenta.secssim.app.event;

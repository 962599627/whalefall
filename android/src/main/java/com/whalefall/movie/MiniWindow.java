package com.whalefall.movie;

/**
 * 1.9.381：小窗实现契约。三端同一套 App，运行时按系统能力分流：
 *
 * <ul>
 *   <li>SDK &lt; 24（MiBox4C / Android 6 等旧机）：{@link MiniDetailsLegacy}，即电视稳定实现。
 *       它不做 TextureView 重挂（旧机重挂在 libmedia_jni SIGSEGV），小窗保留底部 播放/全屏/关闭
 *       操作条与可聚焦视频区，遥控焦点可进入。
 *   <li>SDK &gt;= 24（手机 / 平板 / 新电视）：{@link MiniDetails}，1.9.342 手机实现。
 * </ul>
 */
abstract class MiniWindow {
 abstract void refreshRect();

 abstract void remove();

 abstract boolean pageShown();
}

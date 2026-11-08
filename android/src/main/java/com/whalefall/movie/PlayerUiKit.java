package com.whalefall.movie;

/**
 * 历史遗留类：播放器 UI 已全部迁移到成熟组件与 vector 图标。
 *
 * <ul>
 *   <li>图标：{@link UiIcon}（media3-ui 官方 Material 图标 + {@code res/drawable/ic_whale_*.xml}）
 *   <li>进度条：{@code androidx.media3.ui.DefaultTimeBar}（{@code res/layout/whale_time_bar.xml}）
 *   <li>设计 token：{@link UiTokens}（颜色 / 间距 / 圆角 / 字号 / 动效）
 *   <li>工具面板：{@link PlayerToolsSheet}
 * </ul>
 *
 * <p>此类保留为空壳仅为避免旧构建缓存的引用报错，新代码不要再使用。
 *
 * @deprecated 全部能力已迁出，见上述类。
 */
@Deprecated
final class PlayerUiKit {
    private PlayerUiKit() {}
}

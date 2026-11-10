package com.whalefall.movie;

/** 1.8.18「云盘专区 · 本地」入口（实现位于 CloudDriveZoneScreen，仅做 App 本地直连登录）。 */
final class CloudZoneScreen {
    private CloudZoneScreen() {}

    static void show(MainActivity a) {
        CloudDriveZoneScreen.show(a);
    }
}

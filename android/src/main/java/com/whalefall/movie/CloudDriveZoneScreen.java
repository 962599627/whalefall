package com.whalefall.movie;

/** 1.8.21：旧「云盘专区」列表页已改为网格页（CloudZoneScreen），保留入口兼容。 */
final class CloudDriveZoneScreen {
    private CloudDriveZoneScreen() {}

    static void show(MainActivity a) {
        CloudZoneScreen.show(a);
    }
}

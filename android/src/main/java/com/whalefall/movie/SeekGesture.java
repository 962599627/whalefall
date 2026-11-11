package com.whalefall.movie;

/**
 * Remote-key timeline preview and touch-drag gesture math for seek, brightness, and volume
 * controls.
 */
final class SeekGesture {
    static final int TAP_MS = 10000, HOLD_DELAY_MS = 500;
    static final int STEP_START_MS = 1000, STEP_MAX_MS = 30000, STEP_INTERVAL_MS = 500;
    static final int MODE_NONE = 0, MODE_SEEK = 1, MODE_BRIGHTNESS = 2, MODE_VOLUME = 3;
    boolean active;
    int direction, target, duration, initialTarget;
    long started, updated;
    /** 1.9.61：长按加速步长（1s→2s→4s→8s…上限 30s）。 */
    int stepMs = STEP_START_MS;
    long nextStepAt;

    static int classifyDrag(float dx, float dy, float startX, float width, float touchSlop) {
        if (Math.abs(dx) < touchSlop && Math.abs(dy) < touchSlop) return MODE_NONE;
        if (Math.abs(dx) >= Math.abs(dy) * 1.15f) return MODE_SEEK;
        if (Math.abs(dy) > Math.abs(dx)) {
            return startX < width * 0.5f ? MODE_BRIGHTNESS : MODE_VOLUME;
        }
        return MODE_NONE;
    }

    static int dragSeekTarget(int startPositionMs, int durationMs, float dx, float width) {
        if (durationMs <= 0 || width <= 0) return 0;
        int span = Math.max(60000, Math.min(300000, durationMs / 5));
        long delta = Math.round((dx / width) * span);
        return (int) Math.max(0L, Math.min(durationMs, (long) startPositionMs + delta));
    }

    static float dragVerticalLevel(float startLevel, float dy, float height) {
        float clampedStart = Math.max(0f, Math.min(1f, startLevel));
        if (height <= 0) return clampedStart;
        return Math.max(0f, Math.min(1f, clampedStart - dy / (height * 0.65f)));
    }

    void begin(int direction, int position, int duration, long now) {
        this.direction = direction < 0 ? -1 : 1;
        this.duration = Math.max(0, duration);
        initialTarget = target = bound((long) position + this.direction * TAP_MS);
        started = updated = now;
        stepMs = STEP_START_MS;
        nextStepAt = now + HOLD_DELAY_MS;
        active = duration > 0;
    }

    void advance(long now) {
        if (!active || now < nextStepAt) return;
        // 长按连续加速：每 STEP_INTERVAL 累加当前步长，步长翻倍到 30s 上限。
        while (active && now >= nextStepAt) {
            target = bound((long) target + direction * stepMs);
            stepMs = Math.min(STEP_MAX_MS, stepMs * 2);
            nextStepAt += STEP_INTERVAL_MS;
            updated = now;
        }
    }

    boolean accelerated(long now) {
        return active && now - started > HOLD_DELAY_MS;
    }

    int speed(long now) {
        if (!active || now - started <= HOLD_DELAY_MS) return 0;
        return stepMs;
    }

    int bound(long value) {
        return (int) Math.max(0, Math.min(duration, value));
    }

    void cancel() {
        active = false;
    }
}

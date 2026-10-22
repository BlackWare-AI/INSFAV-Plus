package com.instagram.insfav;

/** Root-screen exit confirmation using a monotonic clock supplied by the Activity. */
final class DoubleBackExit {
    private long firstPress = -1;

    boolean press(long now) {
        boolean exit = firstPress >= 0 && now >= firstPress && now - firstPress <= 2000;
        firstPress = exit ? -1 : now;
        return exit;
    }

    void reset() { firstPress = -1; }
}

package ru.argentoz;

final class PowerOfTwo {

    private PowerOfTwo() {
    }

    static int ceil(int value) {
        if (value <= 1) {
            return 1;
        }
        if (value > (1 << 30)) {
            throw new IllegalArgumentException("value is too large for power-of-two rounding: " + value);
        }
        return Integer.highestOneBit(value - 1) << 1;
    }
}

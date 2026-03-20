package ru.argentoz;

public record DirtyRegion(int x, int y, int width, int height) {

    public DirtyRegion {
        if (x < 0) {
            throw new IllegalArgumentException("x must be >= 0");
        }
        if (y < 0) {
            throw new IllegalArgumentException("y must be >= 0");
        }
        if (width <= 0) {
            throw new IllegalArgumentException("width must be > 0");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be > 0");
        }
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }
}

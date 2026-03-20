package ru.argentoz;

public enum AtlasFormat {
    ALPHA8(1),
    RGBA8(4);

    private final int bytesPerPixel;

    AtlasFormat(int bytesPerPixel) {
        this.bytesPerPixel = bytesPerPixel;
    }

    public int bytesPerPixel() {
        return bytesPerPixel;
    }
}

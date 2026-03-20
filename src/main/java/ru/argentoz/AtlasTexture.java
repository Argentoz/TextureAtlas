package ru.argentoz;

public final class AtlasTexture {

    private final TextureAtlas atlas;
    private int x;
    private int y;
    private int width;
    private int height;
    private boolean alive;

    AtlasTexture(TextureAtlas atlas) {
        this.atlas = atlas;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean isAlive() {
        return alive;
    }

    public float u0() {
        return x / (float) atlas.width();
    }

    public float v0() {
        return y / (float) atlas.height();
    }

    public float u1() {
        return (x + width) / (float) atlas.width();
    }

    public float v1() {
        return (y + height) / (float) atlas.height();
    }

    TextureAtlas atlas() {
        return atlas;
    }

    void setRegion(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.alive = true;
    }

    void invalidate() {
        this.x = 0;
        this.y = 0;
        this.width = 0;
        this.height = 0;
        this.alive = false;
    }
}

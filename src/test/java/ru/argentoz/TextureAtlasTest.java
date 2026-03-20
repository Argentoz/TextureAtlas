package ru.argentoz;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAtlasTest {

    @Test
    void sameSizeUpdateRemoveAndDirtyTrackingWork() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.RGBA8, 8, 16);

        byte[] initial = rgbaBytes(2, 2, 1);
        AtlasTexture texture = atlas.addTexture(initial, 2, 2);

        assertTrue(texture.isAlive());
        assertEquals(8, atlas.width());
        assertEquals(2, atlas.height());
        assertArrayEquals(new DirtyRegion[]{new DirtyRegion(0, 0, 8, 2)}, atlas.consumeDirtyRegions());
        assertTexturePixels(atlas, texture, initial);

        byte[] updated = rgbaBytes(2, 2, 17);
        AtlasTexture sameTexture = atlas.updateTexture(texture, updated, 2, 2);

        assertSame(texture, sameTexture);
        assertArrayEquals(new DirtyRegion[]{new DirtyRegion(0, 0, 2, 2)}, atlas.consumeDirtyRegions());
        assertTexturePixels(atlas, texture, updated);

        assertTrue(atlas.removeTexture(texture));
        assertFalse(texture.isAlive());
        assertEquals(1, atlas.height());
        assertArrayEquals(new DirtyRegion[]{new DirtyRegion(0, 0, 8, 1)}, atlas.consumeDirtyRegions());
        assertRowsAreZero(atlas, 2);
    }

    @Test
    void addFallsBackToRepackWhenAppendPathRunsOutOfSpace() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 8, 8);

        AtlasTexture t1 = atlas.addTexture(alphaBytes(4, 4, 1), 4, 4);
        AtlasTexture t2 = atlas.addTexture(alphaBytes(4, 4, 2), 4, 4);
        AtlasTexture t3 = atlas.addTexture(alphaBytes(4, 4, 3), 4, 4);
        AtlasTexture t4 = atlas.addTexture(alphaBytes(4, 4, 4), 4, 4);
        atlas.consumeDirtyRegions();

        assertTrue(atlas.removeTexture(t2));
        atlas.consumeDirtyRegions();

        AtlasTexture t5 = atlas.addTexture(alphaBytes(4, 4, 5), 4, 4);

        assertTrue(t5.isAlive());
        assertEquals(8, atlas.height());
        assertArrayEquals(new DirtyRegion[]{new DirtyRegion(0, 0, 8, 8)}, atlas.consumeDirtyRegions());

        Set<String> occupiedCells = new HashSet<>();
        occupiedCells.add(t1.x() + "," + t1.y());
        occupiedCells.add(t3.x() + "," + t3.y());
        occupiedCells.add(t4.x() + "," + t4.y());
        occupiedCells.add(t5.x() + "," + t5.y());
        assertEquals(Set.of("0,0", "4,0", "0,4", "4,4"), occupiedCells);

        assertFalse(t2.isAlive());
        assertTexturePixels(atlas, t5, alphaBytes(4, 4, 5));
    }

    @Test
    void resizeKeepsHandleAndUvUseCurrentAtlasHeight() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 8, 16);

        AtlasTexture texture = atlas.addTexture(alphaBytes(4, 4, 7), 4, 4);
        atlas.consumeDirtyRegions();
        assertEquals(1.0f, texture.v1(), 1.0e-6f);

        AtlasTexture neighbour = atlas.addTexture(alphaBytes(4, 5, 11), 4, 5);
        assertEquals(8, atlas.height());
        assertEquals(0.5f, texture.v1(), 1.0e-6f);

        assertTrue(atlas.removeTexture(neighbour));
        assertEquals(4, atlas.height());
        assertEquals(1.0f, texture.v1(), 1.0e-6f);
        atlas.consumeDirtyRegions();

        AtlasTexture sameHandle = atlas.updateTexture(texture, alphaBytes(4, 6, 21), 4, 6);

        assertSame(texture, sameHandle);
        assertEquals(4, texture.width());
        assertEquals(6, texture.height());
        assertEquals(8, atlas.height());
        assertEquals(0.75f, texture.v1(), 1.0e-6f);
        assertArrayEquals(new DirtyRegion[]{new DirtyRegion(0, 0, 8, 8)}, atlas.consumeDirtyRegions());
        assertTexturePixels(atlas, texture, alphaBytes(4, 6, 21));
    }

    @Test
    void clampsHeightToMaxHeightWhenNextPowerOfTwoWouldOverflow() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 8, 10);
        AtlasTexture large = atlas.addTexture(alphaBytes(8, 8, 1), 8, 8);
        atlas.consumeDirtyRegions();

        AtlasTexture extra = atlas.addTexture(alphaBytes(1, 1, 2), 1, 1);

        assertTrue(large.isAlive());
        assertTrue(extra.isAlive());
        assertEquals(10, atlas.height());
        assertEquals(8, extra.y());
        assertEquals(0.9f, extra.v1(), 1.0e-6f);
        assertArrayEquals(new DirtyRegion[]{new DirtyRegion(0, 0, 8, 10)}, atlas.consumeDirtyRegions());
        assertTexturePixels(atlas, extra, alphaBytes(1, 1, 2));
    }

    private static byte[] alphaBytes(int width, int height, int value) {
        byte[] bytes = new byte[width * height];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static byte[] rgbaBytes(int width, int height, int value) {
        byte[] bytes = new byte[width * height * 4];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (value + i);
        }
        return bytes;
    }

    private static void assertTexturePixels(TextureAtlas atlas, AtlasTexture texture, byte[] expectedBytes) {
        int bytesPerPixel = atlas.format().bytesPerPixel();
        int rowBytes = texture.width() * bytesPerPixel;
        byte[] actualBytes = new byte[expectedBytes.length];

        for (int row = 0; row < texture.height(); row++) {
            int srcOffset = ((texture.y() + row) * atlas.width() + texture.x()) * bytesPerPixel;
            System.arraycopy(atlas.pixels(), srcOffset, actualBytes, row * rowBytes, rowBytes);
        }

        assertArrayEquals(expectedBytes, actualBytes);
    }

    private static void assertRowsAreZero(TextureAtlas atlas, int rows) {
        int length = rows * atlas.width() * atlas.format().bytesPerPixel();
        for (int i = 0; i < length; i++) {
            assertEquals(0, atlas.pixels()[i], "byte index " + i + " must be zero");
        }
    }
}

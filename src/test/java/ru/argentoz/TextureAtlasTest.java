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
    void paddingAddsGapWithoutChangingHandleSize() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 8, 8, 1);

        AtlasTexture first = atlas.addTexture(alphaBytes(2, 2, 7), 2, 2);
        AtlasTexture second = atlas.addTexture(alphaBytes(2, 2, 9), 2, 2);

        assertEquals(1, atlas.padding());
        assertEquals(0, first.x());
        assertEquals(0, first.y());
        assertEquals(3, second.x());
        assertEquals(0, second.y());
        assertEquals(2, second.width());
        assertEquals(2, second.height());
        assertEquals(2, atlas.height());
        assertGapIsZero(atlas, 2, 0, 1, 2);
        assertTexturePixels(atlas, first, alphaBytes(2, 2, 7));
        assertTexturePixels(atlas, second, alphaBytes(2, 2, 9));
    }

    @Test
    void paddingAffectsRowAdvanceEvenWhenAtlasHeightIgnoresTrailingPadding() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 4, 16, 2);

        AtlasTexture first = atlas.addTexture(alphaBytes(1, 1, 1), 1, 1);
        assertEquals(1, atlas.height());

        AtlasTexture second = atlas.addTexture(alphaBytes(1, 1, 2), 1, 1);

        assertEquals(0, first.x());
        assertEquals(0, first.y());
        assertEquals(0, second.x());
        assertEquals(3, second.y());
        assertEquals(4, atlas.height());
        assertGapIsZero(atlas, 0, 1, 1, 2);
    }

    @Test
    void addReusesDeletedSpaceAndTracksLeftoverFreeArea() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 8, 8);

        AtlasTexture t1 = atlas.addTexture(alphaBytes(4, 4, 1), 4, 4);
        AtlasTexture t2 = atlas.addTexture(alphaBytes(4, 4, 2), 4, 4);
        AtlasTexture t3 = atlas.addTexture(alphaBytes(4, 4, 3), 4, 4);
        atlas.consumeDirtyRegions();

        assertTrue(atlas.removeTexture(t2));
        assertArrayEquals(new DirtyRegion[]{new DirtyRegion(4, 0, 4, 4)}, atlas.consumeDirtyRegions());

        AtlasTexture t4 = atlas.addTexture(alphaBytes(2, 4, 4), 2, 4);
        assertEquals(4, t4.x());
        assertEquals(0, t4.y());
        assertArrayEquals(new DirtyRegion[]{new DirtyRegion(4, 0, 2, 4)}, atlas.consumeDirtyRegions());

        AtlasTexture t5 = atlas.addTexture(alphaBytes(2, 4, 5), 2, 4);

        assertTrue(t4.isAlive());
        assertTrue(t5.isAlive());
        assertEquals(8, atlas.height());
        assertEquals(6, t5.x());
        assertEquals(0, t5.y());
        assertArrayEquals(new DirtyRegion[]{new DirtyRegion(6, 0, 2, 4)}, atlas.consumeDirtyRegions());

        Set<String> occupiedCells = new HashSet<>();
        occupiedCells.add(t1.x() + "," + t1.y());
        occupiedCells.add(t3.x() + "," + t3.y());
        occupiedCells.add(t4.x() + "," + t4.y());
        occupiedCells.add(t5.x() + "," + t5.y());
        assertEquals(Set.of("0,0", "4,0", "6,0", "0,4"), occupiedCells);

        assertFalse(t2.isAlive());
        assertTexturePixels(atlas, t5, alphaBytes(2, 4, 5));
    }

    @Test
    void splitFreeSpaceStoresRightAndBottomRemainders() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 8, 8);

        AtlasTexture left = atlas.addTexture(alphaBytes(4, 4, 1), 4, 4);
        atlas.addTexture(alphaBytes(4, 4, 2), 4, 4);
        atlas.consumeDirtyRegions();

        assertTrue(atlas.removeTexture(left));
        atlas.consumeDirtyRegions();

        AtlasTexture small = atlas.addTexture(alphaBytes(2, 2, 3), 2, 2);
        assertEquals(0, small.x());
        assertEquals(0, small.y());
        atlas.consumeDirtyRegions();

        AtlasTexture rightRemainder = atlas.addTexture(alphaBytes(2, 4, 4), 2, 4);
        assertEquals(2, rightRemainder.x());
        assertEquals(0, rightRemainder.y());
        atlas.consumeDirtyRegions();

        AtlasTexture bottomRemainder = atlas.addTexture(alphaBytes(2, 2, 5), 2, 2);
        assertEquals(0, bottomRemainder.x());
        assertEquals(2, bottomRemainder.y());
        assertArrayEquals(new DirtyRegion[]{new DirtyRegion(0, 2, 2, 2)}, atlas.consumeDirtyRegions());

        assertTexturePixels(atlas, small, alphaBytes(2, 2, 3));
        assertTexturePixels(atlas, rightRemainder, alphaBytes(2, 4, 4));
        assertTexturePixels(atlas, bottomRemainder, alphaBytes(2, 2, 5));
    }

    @Test
    void addFallsBackToRepackWhenNoSingleHoleFits() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 8, 8);

        AtlasTexture t1 = atlas.addTexture(alphaBytes(4, 4, 1), 4, 4);
        AtlasTexture t2 = atlas.addTexture(alphaBytes(4, 4, 2), 4, 4);
        AtlasTexture t3 = atlas.addTexture(alphaBytes(4, 4, 3), 4, 4);
        AtlasTexture t4 = atlas.addTexture(alphaBytes(4, 4, 4), 4, 4);
        atlas.consumeDirtyRegions();

        assertTrue(atlas.removeTexture(t1));
        assertTrue(atlas.removeTexture(t4));
        atlas.consumeDirtyRegions();

        AtlasTexture wide = atlas.addTexture(alphaBytes(8, 4, 9), 8, 4);

        assertTrue(wide.isAlive());
        assertEquals(8, atlas.height());
        assertEquals(0, wide.x());
        assertEquals(0, wide.y());
        assertArrayEquals(new DirtyRegion[]{new DirtyRegion(0, 0, 8, 8)}, atlas.consumeDirtyRegions());

        Set<String> occupiedCells = new HashSet<>();
        occupiedCells.add(wide.x() + "," + wide.y());
        occupiedCells.add(t2.x() + "," + t2.y());
        occupiedCells.add(t3.x() + "," + t3.y());
        assertEquals(Set.of("0,0", "0,4", "4,4"), occupiedCells);
        assertTexturePixels(atlas, wide, alphaBytes(8, 4, 9));
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

    private static void assertGapIsZero(TextureAtlas atlas, int x, int y, int width, int height) {
        int bytesPerPixel = atlas.format().bytesPerPixel();
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int offset = ((y + row) * atlas.width() + x + column) * bytesPerPixel;
                for (int channel = 0; channel < bytesPerPixel; channel++) {
                    assertEquals(0, atlas.pixels()[offset + channel],
                        "gap pixel at (" + (x + column) + ", " + (y + row) + ") must be zero");
                }
            }
        }
    }
}

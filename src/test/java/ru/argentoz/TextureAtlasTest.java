package ru.argentoz;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void smallDeletedSpaceIsIgnoredAsFreeRect() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 8, 8);

        AtlasTexture t1 = atlas.addTexture(alphaBytes(4, 4, 1), 4, 4);
        AtlasTexture t2 = atlas.addTexture(alphaBytes(4, 4, 2), 4, 4);
        AtlasTexture t3 = atlas.addTexture(alphaBytes(4, 4, 3), 4, 4);
        atlas.consumeDirtyRegions();

        assertTrue(atlas.removeTexture(t2));
        assertArrayEquals(new DirtyRegion[]{new DirtyRegion(4, 0, 4, 4)}, atlas.consumeDirtyRegions());
        assertArrayEquals(new DirtyRegion[0], atlas.freeRegions());

        AtlasTexture t4 = atlas.addTexture(alphaBytes(2, 4, 4), 2, 4);
        assertTrue(t4.isAlive());
        assertEquals(8, atlas.height());
        assertLiveTextureSlotsDoNotOverlap(new ArrayList<>(List.of(t1, t3, t4)),
            atlas.padding(), 0, 0, new StringBuilder("small free-rects are dropped"));
        assertFalse(t2.isAlive());
        assertTexturePixels(atlas, t4, alphaBytes(2, 4, 4));
    }

    @Test
    void splitFreeSpaceDropsRemaindersBelowThreshold() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 8, 8);

        AtlasTexture left = atlas.addTexture(alphaBytes(4, 4, 1), 4, 4);
        atlas.addTexture(alphaBytes(4, 4, 2), 4, 4);
        atlas.consumeDirtyRegions();

        assertTrue(atlas.removeTexture(left));
        atlas.consumeDirtyRegions();
        assertArrayEquals(new DirtyRegion[0], atlas.freeRegions());

        AtlasTexture small = atlas.addTexture(alphaBytes(2, 2, 3), 2, 2);
        assertEquals(0, small.x());
        assertEquals(4, small.y());
        assertArrayEquals(new DirtyRegion[0], atlas.freeRegions());

        assertTexturePixels(atlas, small, alphaBytes(2, 2, 3));
    }

    @Test
    void resizeSmallerDropsLeftoversBelowFreeRectThreshold() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 8, 8);

        AtlasTexture texture = atlas.addTexture(alphaBytes(4, 4, 1), 4, 4);
        atlas.addTexture(alphaBytes(4, 4, 2), 4, 4);
        atlas.consumeDirtyRegions();

        AtlasTexture sameHandle = atlas.updateTexture(texture, alphaBytes(2, 2, 3), 2, 2);

        assertSame(texture, sameHandle);
        assertEquals(0, texture.x());
        assertEquals(0, texture.y());
        assertEquals(2, texture.width());
        assertEquals(2, texture.height());
        assertEquals(4, atlas.height());
        assertArrayEquals(new DirtyRegion[]{new DirtyRegion(0, 0, 4, 4)}, atlas.consumeDirtyRegions());
        assertTexturePixels(atlas, texture, alphaBytes(2, 2, 3));
        assertGapIsZero(atlas, 2, 0, 2, 4);
        assertGapIsZero(atlas, 0, 2, 2, 2);
        assertArrayEquals(new DirtyRegion[0], atlas.freeRegions());
    }

    @Test
    void resizeLargerFallsBackToRepackWhenOnlySmallHoleExists() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 8, 8);

        AtlasTexture texture = atlas.addTexture(alphaBytes(2, 2, 1), 2, 2);
        AtlasTexture neighbour = atlas.addTexture(alphaBytes(2, 2, 2), 2, 2);
        AtlasTexture hole = atlas.addTexture(alphaBytes(4, 4, 3), 4, 4);
        atlas.addTexture(alphaBytes(4, 4, 4), 4, 4);
        atlas.consumeDirtyRegions();

        assertTrue(atlas.removeTexture(hole));
        atlas.consumeDirtyRegions();

        AtlasTexture sameHandle = atlas.updateTexture(texture, alphaBytes(4, 4, 5), 4, 4);

        assertSame(texture, sameHandle);
        assertEquals(8, atlas.height());
        assertArrayEquals(new DirtyRegion[]{new DirtyRegion(0, 0, 8, 8)}, atlas.consumeDirtyRegions());
        assertTexturePixels(atlas, texture, alphaBytes(4, 4, 5));
        assertLiveTextureSlotsDoNotOverlap(new ArrayList<>(List.of(texture, neighbour)),
            atlas.padding(), 0, 0, new StringBuilder("small holes should not be reused"));
    }

    @Test
    void growUpdateThatAppendsOverOldSlotRemovesOverlappingFreeRect() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 8, 8, 1);

        AtlasTexture texture = atlas.addTexture(alphaBytes(2, 3, 1), 2, 3);
        atlas.consumeDirtyRegions();

        AtlasTexture sameHandle = atlas.updateTexture(texture, alphaBytes(4, 3, 2), 4, 3);

        assertSame(texture, sameHandle);
        assertEquals(0, texture.x());
        assertEquals(0, texture.y());
        assertEquals(4, texture.width());
        assertEquals(3, texture.height());
        assertArrayEquals(new DirtyRegion[0], atlas.freeRegions());
    }

    @Test
    void freeRectPlacementResetsAppendCursorBeforeNextAppend() {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 8, 8, 1);

        AtlasTexture left = atlas.addTexture(alphaBytes(2, 4, 1), 2, 4);
        AtlasTexture topRight = atlas.addTexture(alphaBytes(4, 3, 2), 4, 3);
        AtlasTexture bottomRight = atlas.addTexture(alphaBytes(2, 3, 3), 2, 3);

        assertTrue(atlas.removeTexture(topRight));
        AtlasTexture moved = atlas.updateTexture(bottomRight, alphaBytes(3, 2, 4), 3, 2);
        AtlasTexture refilled = atlas.addTexture(alphaBytes(2, 3, 5), 2, 3);

        assertSame(bottomRight, moved);
        assertTrue(left.isAlive());
        assertTrue(refilled.isAlive());
        assertLiveTextureSlotsDoNotOverlap(new ArrayList<>(List.of(left, moved, refilled)),
            atlas.padding(), 0, 0, new StringBuilder("deterministic append-cursor regression"));
        assertThrows(IllegalStateException.class, () -> atlas.addTexture(alphaBytes(4, 3, 6), 4, 3));
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

    @Test
    void randomMutationsNeverExposeOccupiedSpaceAsFreeRegion() {
        for (int seed = 0; seed < 200; seed++) {
            TextureAtlas atlas = new TextureAtlas(AtlasFormat.ALPHA8, 8, 8, 1);
            Random random = new Random(seed);
            ArrayList<AtlasTexture> textures = new ArrayList<>();
            int nextValue = 1;
            StringBuilder history = new StringBuilder();

            for (int step = 0; step < 200; step++) {
                int action = textures.isEmpty() ? 0 : random.nextInt(3);
                if (action == 0) {
                    int width = 1 + random.nextInt(4);
                    int height = 1 + random.nextInt(4);
                    history.append(step).append(": add ").append(width).append('x').append(height);
                    try {
                        textures.add(atlas.addTexture(alphaBytes(width, height, nextValue++), width, height));
                        history.append(" ok");
                    } catch (IllegalStateException ignored) {
                        // Full atlas is acceptable here, we only care about free-space integrity.
                        history.append(" fail");
                    }
                } else if (action == 1) {
                    AtlasTexture texture = textures.get(random.nextInt(textures.size()));
                    int width = 1 + random.nextInt(4);
                    int height = 1 + random.nextInt(4);
                    history.append(step).append(": update #").append(textures.indexOf(texture))
                        .append(" -> ").append(width).append('x').append(height);
                    try {
                        atlas.updateTexture(texture, alphaBytes(width, height, nextValue++), width, height);
                        history.append(" ok");
                    } catch (IllegalStateException ignored) {
                        // Oversized update is acceptable here, the atlas state must remain valid.
                        history.append(" fail");
                    }
                } else {
                    int index = random.nextInt(textures.size());
                    AtlasTexture texture = textures.remove(index);
                    atlas.removeTexture(texture);
                    history.append(step).append(": remove #").append(index);
                }

                history.append(" | state=").append(formatState(atlas, textures)).append('\n');

                assertLiveTextureSlotsDoNotOverlap(textures, atlas.padding(), seed, step, history);
                assertFreeRegionsDoNotOverlapLiveTextureSlots(atlas, textures, seed, step, history);
            }
        }
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

    private static void assertFreeRegionsDoNotOverlapLiveTextureSlots(TextureAtlas atlas,
                                                                      ArrayList<AtlasTexture> textures,
                                                                      int seed,
                                                                      int step,
                                                                      StringBuilder history) {
        List<DirtyRegion> freeRegions = internalFreeRegions(atlas);
        int padding = atlas.padding();

        for (DirtyRegion freeRegion : freeRegions) {
            for (AtlasTexture texture : textures) {
                if (!texture.isAlive()) {
                    continue;
                }

                int occupiedX = texture.x();
                int occupiedY = texture.y();
                int occupiedRight = occupiedX + texture.width() + padding;
                int occupiedBottom = occupiedY + texture.height() + padding;

                boolean overlaps = freeRegion.x() < occupiedRight
                    && freeRegion.x() + freeRegion.width() > occupiedX
                    && freeRegion.y() < occupiedBottom
                    && freeRegion.y() + freeRegion.height() > occupiedY;
                assertFalse(overlaps, () -> "free region " + freeRegion + " overlaps texture slot at seed="
                    + seed + ", step=" + step + ", texture=(" + texture.x() + "," + texture.y() + ","
                    + texture.width() + "x" + texture.height() + "), atlasHeight=" + atlas.height()
                    + "\nHistory:\n" + history);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<DirtyRegion> internalFreeRegions(TextureAtlas atlas) {
        try {
            Field freeRectsField = TextureAtlas.class.getDeclaredField("freeRects");
            freeRectsField.setAccessible(true);

            ArrayList<?> freeRects = (ArrayList<?>) freeRectsField.get(atlas);
            ArrayList<DirtyRegion> regions = new ArrayList<>(freeRects.size());
            for (Object freeRect : freeRects) {
                Class<?> type = freeRect.getClass();
                int x = readIntField(type, freeRect, "x");
                int y = readIntField(type, freeRect, "y");
                int width = readIntField(type, freeRect, "width");
                int height = readIntField(type, freeRect, "height");
                regions.add(new DirtyRegion(x, y, width, height));
            }
            return regions;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to inspect internal free rects", e);
        }
    }

    private static int readIntField(Class<?> type, Object instance, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(instance);
    }

    private static String formatState(TextureAtlas atlas, ArrayList<AtlasTexture> textures) {
        StringBuilder state = new StringBuilder();
        state.append("h=").append(atlas.height()).append(", textures=[");
        for (int i = 0; i < textures.size(); i++) {
            if (i > 0) {
                state.append("; ");
            }
            AtlasTexture texture = textures.get(i);
            state.append(i).append(':')
                .append(texture.x()).append(',').append(texture.y())
                .append(',').append(texture.width()).append('x').append(texture.height())
                .append(",alive=").append(texture.isAlive());
        }
        state.append("], free=").append(internalFreeRegions(atlas));
        return state.toString();
    }

    private static void assertLiveTextureSlotsDoNotOverlap(ArrayList<AtlasTexture> textures,
                                                           int padding,
                                                           int seed,
                                                           int step,
                                                           StringBuilder history) {
        for (int i = 0; i < textures.size(); i++) {
            AtlasTexture first = textures.get(i);
            if (!first.isAlive()) {
                continue;
            }
            int firstRight = first.x() + first.width() + padding;
            int firstBottom = first.y() + first.height() + padding;

            for (int j = i + 1; j < textures.size(); j++) {
                AtlasTexture second = textures.get(j);
                if (!second.isAlive()) {
                    continue;
                }
                int secondRight = second.x() + second.width() + padding;
                int secondBottom = second.y() + second.height() + padding;

                boolean overlaps = first.x() < secondRight
                    && firstRight > second.x()
                    && first.y() < secondBottom
                    && firstBottom > second.y();
                assertFalse(overlaps, () -> "texture slots overlap at seed=" + seed + ", step=" + step
                    + ": first=(" + first.x() + "," + first.y() + "," + first.width() + "x" + first.height()
                    + "), second=(" + second.x() + "," + second.y() + "," + second.width() + "x" + second.height()
                    + ")\nHistory:\n" + history);
            }
        }
    }
}

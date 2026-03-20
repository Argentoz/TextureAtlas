package ru.argentoz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

public final class TextureAtlas {

    private static final int DEFAULT_DIRTY_CAPACITY = 16;
    private static final int MIN_FREE_RECT_DIMENSION = 8;
    private static final DirtyRegion[] EMPTY_DIRTY_REGIONS = new DirtyRegion[0];

    private final AtlasFormat format;
    private final int maxWidth;
    private final int maxHeight;
    private final int padding;
    private final int minHeight;
    private final int bytesPerPixel;
    private final int rowStrideBytes;
    private final byte[] pixels;
    private final ArrayList<AtlasTexture> textures = new ArrayList<>();
    private final ArrayList<FreeRect> freeRects = new ArrayList<>();
    private final STBRectPack rectPack = new STBRectPack();
    private final DirtyTracker dirtyTracker = new DirtyTracker(DEFAULT_DIRTY_CAPACITY);

    private byte[] repackScratch;
    private int currentHeight;
    private int contentBottom;
    private int layoutBottom;
    private int appendX;
    private int appendY;
    private int appendRowHeight;

    public TextureAtlas(AtlasFormat format, int maxWidth, int maxHeight) {
        this(format, maxWidth, maxHeight, 0, 1);
    }

    public TextureAtlas(AtlasFormat format, int maxWidth, int maxHeight, int padding) {
        this(format, maxWidth, maxHeight, padding, 1);
    }

    public TextureAtlas(AtlasFormat format, int maxWidth, int maxHeight, int padding, int minHeight) {
        this.format = Objects.requireNonNull(format, "format");
        if (maxWidth <= 0) {
            throw new IllegalArgumentException("maxWidth must be > 0");
        }
        if (maxHeight <= 0) {
            throw new IllegalArgumentException("maxHeight must be > 0");
        }
        if (padding < 0) {
            throw new IllegalArgumentException("padding must be >= 0");
        }
        if (minHeight <= 0) {
            throw new IllegalArgumentException("minHeight must be > 0");
        }
        if (minHeight > maxHeight) {
            throw new IllegalArgumentException("minHeight must be <= maxHeight");
        }

        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.padding = padding;
        this.minHeight = minHeight;
        this.bytesPerPixel = format.bytesPerPixel();
        this.rowStrideBytes = multiplyExact(maxWidth, bytesPerPixel, "maxWidth * bytesPerPixel");
        this.pixels = new byte[toIntExact((long) rowStrideBytes * maxHeight, "atlas pixel buffer is too large")];
        this.currentHeight = minHeight;
    }

    public AtlasFormat format() {
        return format;
    }

    public int width() {
        return maxWidth;
    }

    public int height() {
        return currentHeight;
    }

    public int maxWidth() {
        return maxWidth;
    }

    public int maxHeight() {
        return maxHeight;
    }

    public int padding() {
        return padding;
    }

    public byte[] pixels() {
        return pixels;
    }

    public boolean isDirty() {
        return dirtyTracker.isDirty();
    }

    public DirtyRegion[] dirtyRegions() {
        return dirtyTracker.snapshot();
    }

    public DirtyRegion[] consumeDirtyRegions() {
        return dirtyTracker.consume();
    }

    public DirtyRegion[] freeRegions() {
        if (freeRects.isEmpty()) {
            return EMPTY_DIRTY_REGIONS;
        }
        DirtyRegion[] regions = new DirtyRegion[freeRects.size()];
        int count = 0;
        for (int i = 0; i < freeRects.size(); i++) {
            FreeRect freeRect = freeRects.get(i);
            if (freeRect.y >= currentHeight) {
                continue;
            }
            int clippedHeight = Math.min(freeRect.bottom(), currentHeight) - freeRect.y;
            if (clippedHeight <= 0) {
                continue;
            }
            regions[count++] = new DirtyRegion(freeRect.x, freeRect.y, freeRect.width, clippedHeight);
        }
        if (count == 0) {
            return EMPTY_DIRTY_REGIONS;
        }
        if (count != regions.length) {
            regions = Arrays.copyOf(regions, count);
        }
        return regions;
    }

    public void clearDirty() {
        dirtyTracker.clear();
    }

    public AtlasTexture addTexture(byte[] bytes, int width, int height) {
        validateTextureBytes(bytes, width, height);

        AtlasTexture texture = new AtlasTexture(this);
        if (tryAddToFreeRect(texture, bytes, width, height) || tryAddByAppending(texture, bytes, width, height)) {
            return texture;
        }

        if (!packAllAndApply(texture, bytes, width, height, false)) {
            throw new IllegalStateException("Texture does not fit into the atlas");
        }
        return texture;
    }

    public boolean removeTexture(AtlasTexture texture) {
        AtlasTexture ownedTexture = requireOwnedTexture(texture);
        if (!ownedTexture.isAlive()) {
            return false;
        }
        if (!textures.remove(ownedTexture)) {
            return false;
        }

        int removedX = ownedTexture.x();
        int removedY = ownedTexture.y();
        int removedWidth = ownedTexture.width();
        int removedHeight = ownedTexture.height();
        SlotSize removedSlot = slotSize(removedWidth, removedHeight);
        int previousHeight = currentHeight;

        clearRect(removedX, removedY, removedSlot.width(), removedSlot.height());
        ownedTexture.invalidate();

        if (textures.isEmpty()) {
            resetAtlasState();
        } else {
            refreshLayoutState();
            addFreeRect(removedX, removedY, removedSlot.width(), removedSlot.height());
            resetAppendState();
        }
        finishMutationAfterPossibleShrink(previousHeight, removedX, removedY,
            removedSlot.width(), removedSlot.height());
        return true;
    }

    public AtlasTexture updateTexture(AtlasTexture texture, byte[] bytes, int width, int height) {
        AtlasTexture ownedTexture = requireOwnedTexture(texture);
        if (!ownedTexture.isAlive()) {
            throw new IllegalStateException("Texture handle is not alive");
        }

        validateTextureBytes(bytes, width, height);
        if (ownedTexture.width() == width && ownedTexture.height() == height) {
            copyIntoAtlas(bytes, ownedTexture.x(), ownedTexture.y(), width, height);
            dirtyTracker.mark(ownedTexture.x(), ownedTexture.y(), width, height);
            return ownedTexture;
        }

        if (width <= ownedTexture.width() && height <= ownedTexture.height()) {
            updateTextureWithinSlot(ownedTexture, bytes, width, height);
            return ownedTexture;
        }

        if (!relocateUpdatedTexture(ownedTexture, bytes, width, height)) {
            throw new IllegalStateException("Updated texture does not fit into the atlas");
        }
        return ownedTexture;
    }

    public void repack() {
        if (textures.isEmpty()) {
            return;
        }
        if (!packAllAndApply(null, null, 0, 0, false)) {
            throw new IllegalStateException("Active textures do not fit into the atlas");
        }
    }

    private boolean tryAddToFreeRect(AtlasTexture texture, byte[] bytes, int width, int height) {
        FreePlacement freePlacement = tryPlaceIntoFreeRect(width, height);
        if (freePlacement == null) {
            return false;
        }
        applyFreeRectAdd(texture, bytes, freePlacement.x(), freePlacement.y(), width, height);
        return true;
    }

    private boolean tryAddByAppending(AtlasTexture texture, byte[] bytes, int width, int height) {
        AppendPlacement placement = tryAppend(width, height);
        if (placement == null) {
            return false;
        }
        applyAppendAdd(texture, bytes, placement.x(), placement.y(), width, height,
            placement.newRowHeight(), placement.newHeight());
        return true;
    }

    private void updateTextureWithinSlot(AtlasTexture texture, byte[] bytes, int width, int height) {
        int previousHeight = currentHeight;
        int x = texture.x();
        int y = texture.y();
        SlotSize oldSlot = slotSize(texture.width(), texture.height());
        SlotSize newSlot = slotSize(width, height);

        clearRect(x, y, oldSlot.width(), oldSlot.height());
        copyIntoAtlas(bytes, x, y, width, height);
        texture.setRegion(x, y, width, height);

        addFreeRect(x + newSlot.width(), y, oldSlot.width() - newSlot.width(), oldSlot.height());
        addFreeRect(x, y + newSlot.height(), newSlot.width(), oldSlot.height() - newSlot.height());

        refreshLayoutState();
        resetAppendState();
        finishMutationAfterPossibleShrink(previousHeight, x, y, oldSlot.width(), oldSlot.height());
    }

    private boolean relocateUpdatedTexture(AtlasTexture texture, byte[] bytes, int width, int height) {
        int textureIndex = requireRegisteredTexture(texture);
        AtlasStateSnapshot previousState = snapshotState();

        int oldX = texture.x();
        int oldY = texture.y();
        SlotSize oldSlot = slotSize(texture.width(), texture.height());

        textures.remove(textureIndex);
        addFreeRect(oldX, oldY, oldSlot.width(), oldSlot.height());
        refreshLayoutState();
        resetAppendState();

        if (tryRelocateToFreeRect(texture, textureIndex, bytes, width, height,
            oldX, oldY, oldSlot.width(), oldSlot.height(), previousState.currentHeight())) {
            return true;
        }

        if (tryRelocateByAppending(texture, textureIndex, bytes, width, height,
            oldX, oldY, oldSlot.width(), oldSlot.height(), previousState.currentHeight())) {
            return true;
        }

        restoreState(texture, textureIndex, previousState);
        return packAllAndApply(texture, bytes, width, height, true);
    }

    private boolean tryRelocateToFreeRect(AtlasTexture texture,
                                          int textureIndex,
                                          byte[] bytes,
                                          int width,
                                          int height,
                                          int oldX,
                                          int oldY,
                                          int oldSlotWidth,
                                          int oldSlotHeight,
                                          int previousHeight) {
        FreePlacement freePlacement = tryPlaceIntoFreeRect(width, height);
        if (freePlacement == null) {
            return false;
        }
        applyRelocatedUpdate(texture, textureIndex, bytes, width, height, oldX, oldY,
            oldSlotWidth, oldSlotHeight, freePlacement.x(), freePlacement.y(), false, 0, previousHeight);
        return true;
    }

    private boolean tryRelocateByAppending(AtlasTexture texture,
                                           int textureIndex,
                                           byte[] bytes,
                                           int width,
                                           int height,
                                           int oldX,
                                           int oldY,
                                           int oldSlotWidth,
                                           int oldSlotHeight,
                                           int previousHeight) {
        AppendPlacement appendPlacement = tryAppend(width, height);
        if (appendPlacement == null) {
            return false;
        }
        applyRelocatedUpdate(texture, textureIndex, bytes, width, height, oldX, oldY,
            oldSlotWidth, oldSlotHeight, appendPlacement.x(), appendPlacement.y(), true,
            appendPlacement.newRowHeight(), previousHeight);
        return true;
    }

    private void applyRelocatedUpdate(AtlasTexture texture,
                                      int textureIndex,
                                      byte[] bytes,
                                      int width,
                                      int height,
                                      int oldX,
                                      int oldY,
                                      int oldSlotWidth,
                                      int oldSlotHeight,
                                      int newX,
                                      int newY,
                                      boolean appended,
                                      int newRowHeight,
                                      int previousHeight) {
        SlotSize newSlot = slotSize(width, height);
        int newContentBottom = Math.max(contentBottom, newY + height);
        int newLayoutBottom = Math.max(layoutBottom, newY + newSlot.height());
        int newHeight = atlasHeightForContent(newContentBottom);

        if (newHeight > currentHeight) {
            clearRows(currentHeight, newHeight - currentHeight);
        }

        clearRect(oldX, oldY, oldSlotWidth, oldSlotHeight);
        clearRect(newX, newY, newSlot.width(), newSlot.height());
        copyIntoAtlas(bytes, newX, newY, width, height);
        subtractFreeArea(newX, newY, newSlot.width(), newSlot.height());

        texture.setRegion(newX, newY, width, height);
        textures.add(Math.min(textureIndex, textures.size()), texture);
        contentBottom = newContentBottom;
        layoutBottom = newLayoutBottom;
        currentHeight = newHeight;

        if (appended) {
            appendX = newX + newSlot.width();
            appendY = newY;
            appendRowHeight = newRowHeight;
        } else {
            resetAppendState();
        }

        if (newHeight < previousHeight) {
            clearRows(newHeight, previousHeight - newHeight);
        }

        if (currentHeight != previousHeight) {
            markWholeAtlasDirty();
        } else {
            markDirtyClamped(oldX, oldY, oldSlotWidth, oldSlotHeight);
            markDirtyClamped(newX, newY, newSlot.width(), newSlot.height());
        }
    }

    private AppendPlacement tryAppend(int width, int height) {
        SlotSize slot = slotSize(width, height);
        int candidateX = appendX;
        int candidateY = appendY;
        int candidateRowHeight = appendRowHeight;

        if (candidateX + slot.width() > maxWidth) {
            candidateX = 0;
            candidateY += candidateRowHeight;
            candidateRowHeight = 0;
        }

        int paddedBottom = candidateY + slot.height();
        if (paddedBottom > maxHeight) {
            return null;
        }
        if (!textures.isEmpty() && candidateY + height > currentHeight) {
            return null;
        }

        int newContentBottom = Math.max(contentBottom, candidateY + height);
        int newHeight = atlasHeightForContent(newContentBottom);

        return new AppendPlacement(candidateX, candidateY, Math.max(candidateRowHeight, slot.height()), newHeight);
    }

    private FreePlacement tryPlaceIntoFreeRect(int width, int height) {
        SlotSize slot = slotSize(width, height);
        int bestIndex = -1;
        int bestWaste = Integer.MAX_VALUE;
        int bestShortSideWaste = Integer.MAX_VALUE;
        int bestY = Integer.MAX_VALUE;
        int bestX = Integer.MAX_VALUE;

        for (int i = 0; i < freeRects.size(); i++) {
            FreeRect freeRect = freeRects.get(i);
            if (slot.width() > freeRect.width || slot.height() > freeRect.height) {
                continue;
            }

            int waste = freeRect.width * freeRect.height - slot.width() * slot.height();
            int shortSideWaste = Math.min(freeRect.width - slot.width(), freeRect.height - slot.height());
            if (waste < bestWaste
                || (waste == bestWaste && shortSideWaste < bestShortSideWaste)
                || (waste == bestWaste && shortSideWaste == bestShortSideWaste
                && (freeRect.y < bestY || (freeRect.y == bestY && freeRect.x < bestX)))) {
                bestIndex = i;
                bestWaste = waste;
                bestShortSideWaste = shortSideWaste;
                bestX = freeRect.x;
                bestY = freeRect.y;
            }
        }

        if (bestIndex < 0) {
            return null;
        }

        FreeRect freeRect = freeRects.remove(bestIndex);
        // Split the consumed free rect into non-overlapping right and bottom leftovers.
        addFreeRect(freeRect.x + slot.width(), freeRect.y, freeRect.width - slot.width(), freeRect.height);
        addFreeRect(freeRect.x, freeRect.y + slot.height(), slot.width(), freeRect.height - slot.height());
        return new FreePlacement(freeRect.x, freeRect.y);
    }

    private void applyFreeRectAdd(AtlasTexture texture, byte[] bytes, int x, int y, int width, int height) {
        int previousHeight = currentHeight;
        SlotSize slot = slotSize(width, height);
        int newContentBottom = Math.max(contentBottom, y + height);
        int newHeight = atlasHeightForContent(newContentBottom);
        if (newHeight > previousHeight) {
            clearRows(previousHeight, newHeight - previousHeight);
        }

        clearRect(x, y, slot.width(), slot.height());
        copyIntoAtlas(bytes, x, y, width, height);
        texture.setRegion(x, y, width, height);
        textures.add(texture);
        contentBottom = newContentBottom;
        layoutBottom = Math.max(layoutBottom, y + slot.height());
        currentHeight = newHeight;
        resetAppendState();
        finishMutationAfterAnyHeightChange(previousHeight, x, y, width, height);
    }

    private void applyAppendAdd(AtlasTexture texture,
                                byte[] bytes,
                                int x,
                                int y,
                                int width,
                                int height,
                                int newRowHeight,
                                int newHeight) {
        int previousHeight = currentHeight;
        SlotSize slot = slotSize(width, height);
        if (newHeight > previousHeight) {
            clearRows(previousHeight, newHeight - previousHeight);
        }

        clearRect(x, y, slot.width(), slot.height());
        copyIntoAtlas(bytes, x, y, width, height);
        subtractFreeArea(x, y, slot.width(), slot.height());

        texture.setRegion(x, y, width, height);
        textures.add(texture);
        contentBottom = Math.max(contentBottom, y + height);
        layoutBottom = Math.max(layoutBottom, y + slot.height());
        currentHeight = newHeight;
        appendX = x + slot.width();
        appendY = y;
        appendRowHeight = newRowHeight;
        finishMutationAfterAnyHeightChange(previousHeight, x, y, width, height);
    }

    private boolean packAllAndApply(AtlasTexture specialTexture,
                                    byte[] specialBytes,
                                    int specialWidth,
                                    int specialHeight,
                                    boolean specialAlreadyActive) {
        PackedLayout packedLayout = buildPackedLayoutWithGrowth(specialTexture, specialWidth,
            specialHeight, specialAlreadyActive);
        if (packedLayout == null) {
            return false;
        }

        applyPackedLayout(packedLayout, specialTexture, specialBytes, specialWidth, specialHeight,
            specialAlreadyActive);
        return true;
    }

    private PackedLayout buildPackedLayoutWithGrowth(AtlasTexture specialTexture,
                                                     int specialWidth,
                                                     int specialHeight,
                                                     boolean specialAlreadyActive) {
        int targetHeight = currentHeight;
        while (true) {
            PackedLayout packedLayout = buildPackedLayout(specialTexture, specialWidth, specialHeight,
                specialAlreadyActive, targetHeight);
            if (packedLayout != null) {
                return packedLayout;
            }

            int nextHeight = nextPackHeight(targetHeight);
            if (nextHeight <= targetHeight) {
                return null;
            }
            targetHeight = nextHeight;
        }
    }

    private PackedLayout buildPackedLayout(AtlasTexture specialTexture,
                                           int specialWidth,
                                           int specialHeight,
                                           boolean specialAlreadyActive,
                                           int targetHeight) {
        int extra = specialTexture != null && !specialAlreadyActive ? 1 : 0;
        int count = textures.size() + extra;
        if (count == 0) {
            return PackedLayout.EMPTY;
        }

        AtlasTexture[] handles = new AtlasTexture[count];
        int[] ids = new int[count];
        int[] widths = new int[count];
        int[] heights = new int[count];
        int[] outX = new int[count];
        int[] outY = new int[count];
        boolean[] packed = new boolean[count];

        int index = 0;
        for (AtlasTexture texture : textures) {
            handles[index] = texture;
            ids[index] = index;
            if (texture == specialTexture && specialAlreadyActive) {
                widths[index] = slotWidth(specialWidth);
                heights[index] = slotHeight(specialHeight);
            } else {
                widths[index] = slotWidth(texture.width());
                heights[index] = slotHeight(texture.height());
            }
            index++;
        }
        if (specialTexture != null && !specialAlreadyActive) {
            handles[index] = specialTexture;
            ids[index] = index;
            widths[index] = slotWidth(specialWidth);
            heights[index] = slotHeight(specialHeight);
        }

        rectPack.initTarget(maxWidth, targetHeight, maxWidth);
        rectPack.setHeuristic(STBRectPack.HEURISTIC_BF);
        if (!rectPack.packRects(ids, widths, heights, outX, outY, packed, count)) {
            return null;
        }

        int usedBottom = 0;
        int usedLayoutBottom = 0;
        for (int i = 0; i < count; i++) {
            AtlasTexture texture = handles[i];
            int actualHeight = texture == specialTexture ? specialHeight : texture.height();
            usedBottom = Math.max(usedBottom, outY[i] + actualHeight);
            usedLayoutBottom = Math.max(usedLayoutBottom, outY[i] + heights[i]);
        }
        if (usedLayoutBottom > targetHeight) {
            return null;
        }
        int newHeight = atlasHeightForContent(usedBottom);
        return new PackedLayout(handles, outX, outY, count, usedBottom, usedLayoutBottom, newHeight);
    }

    private void applyPackedLayout(PackedLayout packedLayout,
                                   AtlasTexture specialTexture,
                                   byte[] specialBytes,
                                   int specialWidth,
                                   int specialHeight,
                                   boolean specialAlreadyActive) {
        int previousHeight = currentHeight;
        int previousBytes = rowsToBytes(previousHeight);
        ensureScratchCapacity(previousBytes);
        if (previousBytes > 0) {
            System.arraycopy(pixels, 0, repackScratch, 0, previousBytes);
        }

        int clearRows = Math.max(previousHeight, packedLayout.newHeight());
        Arrays.fill(pixels, 0, rowsToBytes(clearRows), (byte) 0);

        for (int i = 0; i < packedLayout.count(); i++) {
            AtlasTexture texture = packedLayout.handles()[i];
            int newX = packedLayout.outX()[i];
            int newY = packedLayout.outY()[i];
            int actualWidth = texture == specialTexture ? specialWidth : texture.width();
            int actualHeight = texture == specialTexture ? specialHeight : texture.height();

            if (texture == specialTexture) {
                copyIntoAtlas(specialBytes, newX, newY, actualWidth, actualHeight);
            } else {
                copyRegion(repackScratch, texture.x(), texture.y(), actualWidth, actualHeight, pixels, newX, newY);
            }

            texture.setRegion(newX, newY, actualWidth, actualHeight);
        }

        if (specialTexture != null && !specialAlreadyActive) {
            textures.add(specialTexture);
        }

        contentBottom = packedLayout.usedBottom();
        layoutBottom = packedLayout.usedLayoutBottom();
        currentHeight = packedLayout.newHeight();
        freeRects.clear();
        resetAppendState();
        markWholeAtlasDirty();
    }

    private void copyIntoAtlas(byte[] source, int x, int y, int width, int height) {
        int rowBytes = multiplyExact(width, bytesPerPixel, "texture row is too large");
        for (int row = 0; row < height; row++) {
            int srcOffset = row * rowBytes;
            int dstOffset = pixelOffset(x, y + row);
            System.arraycopy(source, srcOffset, pixels, dstOffset, rowBytes);
        }
    }

    private void copyRegion(byte[] source,
                            int sourceX,
                            int sourceY,
                            int width,
                            int height,
                            byte[] destination,
                            int destinationX,
                            int destinationY) {
        int rowBytes = multiplyExact(width, bytesPerPixel, "texture row is too large");
        for (int row = 0; row < height; row++) {
            int srcOffset = pixelOffset(sourceX, sourceY + row);
            int dstOffset = pixelOffset(destinationX, destinationY + row);
            System.arraycopy(source, srcOffset, destination, dstOffset, rowBytes);
        }
    }

    private void clearRect(int x, int y, int width, int height) {
        int rowBytes = multiplyExact(width, bytesPerPixel, "texture row is too large");
        for (int row = 0; row < height; row++) {
            int offset = pixelOffset(x, y + row);
            Arrays.fill(pixels, offset, offset + rowBytes, (byte) 0);
        }
    }

    private void clearRows(int startRow, int rowCount) {
        if (rowCount <= 0) {
            return;
        }
        Arrays.fill(pixels, rowsToBytes(startRow), rowsToBytes(startRow + rowCount), (byte) 0);
    }

    private int recomputeContentBottom() {
        int bottom = 0;
        for (AtlasTexture texture : textures) {
            bottom = Math.max(bottom, texture.y() + texture.height());
        }
        return bottom;
    }

    private int recomputeLayoutBottom() {
        int bottom = 0;
        for (AtlasTexture texture : textures) {
            bottom = Math.max(bottom, texture.y() + slotHeight(texture.height()));
        }
        return bottom;
    }

    private int requireRegisteredTexture(AtlasTexture texture) {
        int textureIndex = textures.indexOf(texture);
        if (textureIndex < 0) {
            throw new IllegalStateException("Texture handle is not registered in the atlas");
        }
        return textureIndex;
    }

    private void refreshLayoutState() {
        contentBottom = recomputeContentBottom();
        layoutBottom = recomputeLayoutBottom();
        currentHeight = atlasHeightForContent(contentBottom);
    }

    private void resetAtlasState() {
        contentBottom = 0;
        layoutBottom = 0;
        currentHeight = minHeight;
        freeRects.clear();
        resetAppendState();
    }

    private AtlasStateSnapshot snapshotState() {
        return new AtlasStateSnapshot(contentBottom, layoutBottom, currentHeight, appendX, appendY,
            appendRowHeight, new ArrayList<>(freeRects));
    }

    private void restoreState(AtlasTexture texture, int textureIndex, AtlasStateSnapshot snapshot) {
        textures.add(Math.min(textureIndex, textures.size()), texture);
        freeRects.clear();
        freeRects.addAll(snapshot.freeRects());
        contentBottom = snapshot.contentBottom();
        layoutBottom = snapshot.layoutBottom();
        currentHeight = snapshot.currentHeight();
        appendX = snapshot.appendX();
        appendY = snapshot.appendY();
        appendRowHeight = snapshot.appendRowHeight();
    }

    private void finishMutationAfterPossibleShrink(int previousHeight,
                                                   int dirtyX,
                                                   int dirtyY,
                                                   int dirtyWidth,
                                                   int dirtyHeight) {
        if (currentHeight < previousHeight) {
            clearRows(currentHeight, previousHeight - currentHeight);
            markWholeAtlasDirty();
            return;
        }
        markDirtyClamped(dirtyX, dirtyY, dirtyWidth, dirtyHeight);
    }

    private void finishMutationAfterAnyHeightChange(int previousHeight,
                                                    int dirtyX,
                                                    int dirtyY,
                                                    int dirtyWidth,
                                                    int dirtyHeight) {
        if (currentHeight != previousHeight) {
            markWholeAtlasDirty();
            return;
        }
        markDirtyClamped(dirtyX, dirtyY, dirtyWidth, dirtyHeight);
    }

    private int nextPackHeight(int currentPackHeight) {
        if (currentPackHeight >= maxHeight) {
            return currentPackHeight;
        }

        int nextPowerOfTwo = PowerOfTwo.ceil(currentPackHeight + 1);
        return Math.min(nextPowerOfTwo, maxHeight);
    }

    private void markWholeAtlasDirty() {
        dirtyTracker.mark(0, 0, maxWidth, currentHeight);
    }

    private void markDirtyClamped(int x, int y, int width, int height) {
        if (y >= currentHeight) {
            return;
        }
        int clippedHeight = Math.min(y + height, currentHeight) - y;
        if (clippedHeight <= 0) {
            return;
        }
        dirtyTracker.mark(x, y, width, clippedHeight);
    }

    private void addFreeRect(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0 || x < 0 || y < 0 || x + width > maxWidth) {
            return;
        }
        if (y >= maxHeight || y + height > maxHeight) {
            return;
        }
        if (width < MIN_FREE_RECT_DIMENSION || height < MIN_FREE_RECT_DIMENSION) {
            return;
        }

        FreeRect mergedRect = new FreeRect(x, y, width, height);
        int index = 0;
        while (index < freeRects.size()) {
            FreeRect existing = freeRects.get(index);
            if (contains(mergedRect, existing)) {
                freeRects.remove(index);
                continue;
            }
            if (contains(existing, mergedRect)) {
                return;
            }
            if (canMerge(mergedRect, existing)) {
                mergedRect = merge(mergedRect, existing);
                freeRects.remove(index);
                index = 0;
                continue;
            }
            index++;
        }
        freeRects.add(mergedRect);
    }

    private void subtractFreeArea(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0 || freeRects.isEmpty()) {
            return;
        }

        int cutRight = x + width;
        int cutBottom = y + height;
        ArrayList<FreeRect> previousFreeRects = new ArrayList<>(freeRects);
        freeRects.clear();

        for (int i = 0; i < previousFreeRects.size(); i++) {
            FreeRect freeRect = previousFreeRects.get(i);
            int overlapX = Math.max(freeRect.x, x);
            int overlapY = Math.max(freeRect.y, y);
            int overlapRight = Math.min(freeRect.right(), cutRight);
            int overlapBottom = Math.min(freeRect.bottom(), cutBottom);

            if (overlapX >= overlapRight || overlapY >= overlapBottom) {
                addFreeRect(freeRect.x, freeRect.y, freeRect.width, freeRect.height);
                continue;
            }

            addFreeRect(freeRect.x, freeRect.y, freeRect.width, overlapY - freeRect.y);
            addFreeRect(freeRect.x, overlapBottom, freeRect.width, freeRect.bottom() - overlapBottom);
            addFreeRect(freeRect.x, overlapY, overlapX - freeRect.x, overlapBottom - overlapY);
            addFreeRect(overlapRight, overlapY, freeRect.right() - overlapRight, overlapBottom - overlapY);
        }
    }

    private static boolean contains(FreeRect outer, FreeRect inner) {
        return outer.x <= inner.x
            && outer.y <= inner.y
            && outer.right() >= inner.right()
            && outer.bottom() >= inner.bottom();
    }

    private static boolean canMerge(FreeRect first, FreeRect second) {
        return (first.y == second.y
            && first.height == second.height
            && first.x <= second.right()
            && second.x <= first.right())
            || (first.x == second.x
            && first.width == second.width
            && first.y <= second.bottom()
            && second.y <= first.bottom());
    }

    private static FreeRect merge(FreeRect first, FreeRect second) {
        int minX = Math.min(first.x, second.x);
        int minY = Math.min(first.y, second.y);
        int maxX = Math.max(first.right(), second.right());
        int maxY = Math.max(first.bottom(), second.bottom());
        return new FreeRect(minX, minY, maxX - minX, maxY - minY);
    }

    private void resetAppendState() {
        appendX = 0;
        appendY = layoutBottom;
        appendRowHeight = 0;
    }

    private void validateTextureBytes(byte[] bytes, int width, int height) {
        Objects.requireNonNull(bytes, "bytes");
        if (width <= 0) {
            throw new IllegalArgumentException("width must be > 0");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be > 0");
        }
        if (width > maxWidth) {
            throw new IllegalArgumentException("width exceeds atlas maxWidth");
        }
        if (height > maxHeight) {
            throw new IllegalArgumentException("height exceeds atlas maxHeight");
        }

        int expectedLength = toIntExact((long) width * height * bytesPerPixel, "texture byte array is too large");
        if (bytes.length != expectedLength) {
            throw new IllegalArgumentException("Expected " + expectedLength + " bytes but got " + bytes.length);
        }
    }

    private AtlasTexture requireOwnedTexture(AtlasTexture texture) {
        AtlasTexture ownedTexture = Objects.requireNonNull(texture, "texture");
        if (ownedTexture.atlas() != this) {
            throw new IllegalArgumentException("Texture handle does not belong to this atlas");
        }
        return ownedTexture;
    }

    private int pixelOffset(int x, int y) {
        return y * rowStrideBytes + x * bytesPerPixel;
    }

    private int atlasHeightForContent(int contentBottom) {
        if (contentBottom <= 0) {
            return minHeight;
        }
        if (contentBottom <= minHeight) {
            return minHeight;
        }
        return Math.min(PowerOfTwo.ceil(contentBottom), maxHeight);
    }

    private SlotSize slotSize(int width, int height) {
        return new SlotSize(slotWidth(width), slotHeight(height));
    }

    private int slotWidth(int width) {
        return toIntExact((long) width + padding, "padded width is too large");
    }

    private int slotHeight(int height) {
        return toIntExact((long) height + padding, "padded height is too large");
    }

    private void ensureScratchCapacity(int requiredBytes) {
        if (repackScratch == null || repackScratch.length < requiredBytes) {
            repackScratch = new byte[requiredBytes];
        }
    }

    private int rowsToBytes(int rows) {
        return toIntExact((long) rows * rowStrideBytes, "row byte count is too large");
    }

    private static int multiplyExact(int left, int right, String message) {
        return toIntExact((long) left * right, message);
    }

    private static int toIntExact(long value, String message) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(message);
        }
        return (int) value;
    }

    private record FreePlacement(int x, int y) {
    }

    private record AppendPlacement(int x, int y, int newRowHeight, int newHeight) {
    }

    private record SlotSize(int width, int height) {
    }

    private record AtlasStateSnapshot(int contentBottom,
                                      int layoutBottom,
                                      int currentHeight,
                                      int appendX,
                                      int appendY,
                                      int appendRowHeight,
                                      ArrayList<FreeRect> freeRects) {
    }

    private record PackedLayout(AtlasTexture[] handles,
                                int[] outX,
                                int[] outY,
                                int count,
                                int usedBottom,
                                int usedLayoutBottom,
                                int newHeight) {

        private static final PackedLayout EMPTY =
            new PackedLayout(new AtlasTexture[0], new int[0], new int[0], 0, 0, 0, 1);
    }

    private static final class FreeRect {

        private final int x;
        private final int y;
        private final int width;
        private int height;

        private FreeRect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        private int right() {
            return x + width;
        }

        private int bottom() {
            return y + height;
        }
    }

    private static final class DirtyTracker {

        private final int[] xs;
        private final int[] ys;
        private final int[] widths;
        private final int[] heights;

        private int count;
        private boolean dirty;

        private DirtyTracker(int capacity) {
            this.xs = new int[capacity];
            this.ys = new int[capacity];
            this.widths = new int[capacity];
            this.heights = new int[capacity];
        }

        private boolean isDirty() {
            return dirty;
        }

        private DirtyRegion[] snapshot() {
            if (count == 0) {
                return EMPTY_DIRTY_REGIONS;
            }
            DirtyRegion[] regions = new DirtyRegion[count];
            for (int i = 0; i < count; i++) {
                regions[i] = new DirtyRegion(xs[i], ys[i], widths[i], heights[i]);
            }
            return regions;
        }

        private DirtyRegion[] consume() {
            DirtyRegion[] regions = snapshot();
            clear();
            return regions;
        }

        private void clear() {
            count = 0;
            dirty = false;
        }

        private void mark(int x, int y, int width, int height) {
            if (width <= 0 || height <= 0) {
                return;
            }

            dirty = true;

            int mergedX = x;
            int mergedY = y;
            int mergedWidth = width;
            int mergedHeight = height;

            int index = 0;
            while (index < count) {
                if (touchesOrOverlaps(mergedX, mergedY, mergedWidth, mergedHeight,
                    xs[index], ys[index], widths[index], heights[index])) {
                    int minX = Math.min(mergedX, xs[index]);
                    int minY = Math.min(mergedY, ys[index]);
                    int maxX = Math.max(mergedX + mergedWidth, xs[index] + widths[index]);
                    int maxY = Math.max(mergedY + mergedHeight, ys[index] + heights[index]);
                    mergedX = minX;
                    mergedY = minY;
                    mergedWidth = maxX - minX;
                    mergedHeight = maxY - minY;
                    removeAt(index);
                    index = 0;
                } else {
                    index++;
                }
            }

            if (count < xs.length) {
                xs[count] = mergedX;
                ys[count] = mergedY;
                widths[count] = mergedWidth;
                heights[count] = mergedHeight;
                count++;
                return;
            }

            collapseToBounds(mergedX, mergedY, mergedWidth, mergedHeight);
        }

        private void collapseToBounds(int x, int y, int width, int height) {
            int minX = x;
            int minY = y;
            int maxX = x + width;
            int maxY = y + height;

            for (int i = 0; i < count; i++) {
                minX = Math.min(minX, xs[i]);
                minY = Math.min(minY, ys[i]);
                maxX = Math.max(maxX, xs[i] + widths[i]);
                maxY = Math.max(maxY, ys[i] + heights[i]);
            }

            xs[0] = minX;
            ys[0] = minY;
            widths[0] = maxX - minX;
            heights[0] = maxY - minY;
            count = 1;
        }

        private void removeAt(int index) {
            int last = count - 1;
            xs[index] = xs[last];
            ys[index] = ys[last];
            widths[index] = widths[last];
            heights[index] = heights[last];
            count = last;
        }

        private boolean touchesOrOverlaps(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
            return ax <= bx + bw && bx <= ax + aw && ay <= by + bh && by <= ay + ah;
        }
    }
}

package ru.argentoz.demo;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.nfd.NFDFilterItem;
import org.lwjgl.util.nfd.NativeFileDialog;
import ru.argentoz.AtlasFormat;
import ru.argentoz.AtlasTexture;
import ru.argentoz.DirtyRegion;
import ru.argentoz.TextureAtlas;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetMouseButton;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_LEFT;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_MIDDLE;
import static org.lwjgl.nanovg.NanoVG.nvgBeginFrame;
import static org.lwjgl.nanovg.NanoVG.nvgBeginPath;
import static org.lwjgl.nanovg.NanoVG.nvgClosePath;
import static org.lwjgl.nanovg.NanoVG.nvgCreateFont;
import static org.lwjgl.nanovg.NanoVG.nvgCreateImageRGBA;
import static org.lwjgl.nanovg.NanoVG.nvgDeleteImage;
import static org.lwjgl.nanovg.NanoVG.nvgEndFrame;
import static org.lwjgl.nanovg.NanoVG.nvgFill;
import static org.lwjgl.nanovg.NanoVG.nvgFillColor;
import static org.lwjgl.nanovg.NanoVG.nvgFillPaint;
import static org.lwjgl.nanovg.NanoVG.nvgFontFace;
import static org.lwjgl.nanovg.NanoVG.nvgFontSize;
import static org.lwjgl.nanovg.NanoVG.nvgImagePattern;
import static org.lwjgl.nanovg.NanoVG.nvgRGBAf;
import static org.lwjgl.nanovg.NanoVG.nvgRect;
import static org.lwjgl.nanovg.NanoVG.nvgRoundedRect;
import static org.lwjgl.nanovg.NanoVG.nvgStroke;
import static org.lwjgl.nanovg.NanoVG.nvgStrokeColor;
import static org.lwjgl.nanovg.NanoVG.nvgStrokeWidth;
import static org.lwjgl.nanovg.NanoVG.nvgText;
import static org.lwjgl.nanovg.NanoVG.nvgTextAlign;
import static org.lwjgl.nanovg.NanoVG.nvgUpdateImage;
import static org.lwjgl.nanovg.NanoVGGL3.NVG_ANTIALIAS;
import static org.lwjgl.nanovg.NanoVGGL3.NVG_STENCIL_STROKES;
import static org.lwjgl.nanovg.NanoVGGL3.nvgCreate;
import static org.lwjgl.nanovg.NanoVGGL3.nvgDelete;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearColor;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memUTF8;

public final class AtlasDemoApp {

    private static final int WINDOW_WIDTH = 1600;
    private static final int WINDOW_HEIGHT = 960;
    private static final int ATLAS_MAX_WIDTH = 2048;
    private static final int ATLAS_MAX_HEIGHT = 4096;
    private static final int ATLAS_PADDING = 2;
    private static final float PADDING = 18.0f;
    private static final float TOOLBAR_HEIGHT = 84.0f;
    private static final float BUTTON_WIDTH = 148.0f;
    private static final float BUTTON_HEIGHT = 36.0f;
    private static final float BUTTON_GAP = 12.0f;
    private static final String UI_FONT_NAME = "ui";
    private final TextureAtlas atlas = new TextureAtlas(AtlasFormat.RGBA8, ATLAS_MAX_WIDTH, ATLAS_MAX_HEIGHT, ATLAS_PADDING, 256);
    private final List<TextureEntry> entries = new ArrayList<>();

    private long window;
    private long vg;
    private int atlasImageId;
    private int uploadedAtlasHeight = -1;
    private ByteBuffer uploadBuffer;
    private GLFWErrorCallback errorCallback;
    private boolean nfdInitialized;
    private PendingAction pendingAction = PendingAction.NONE;

    private TextureEntry selectedEntry;
    private Path lastDialogDirectory;
    private String statusMessage = "Load a texture to populate the atlas.";
    private boolean previousLeftDown;
    private float mouseX;
    private float mouseY;

    public static void main(String[] args) {
        new AtlasDemoApp().run();
    }

    private void run() {
        errorCallback = GLFWErrorCallback.createPrint(System.err);
        errorCallback.set();
        try {
            initWindow();
            initRendering();
            initDialogs();
            loop();
        } finally {
            shutdown();
        }
    }

    private void initWindow() {
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, "Image Atlas Demo", NULL, NULL);
        if (window == NULL) {
            throw new IllegalStateException("Unable to create GLFW window");
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);
    }

    private void initRendering() {
        GL.createCapabilities();
        vg = nvgCreate(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
        if (vg == NULL) {
            throw new IllegalStateException("Unable to create NanoVG context");
        }

        String fontPath = findUiFontPath();
        if (fontPath == null || nvgCreateFont(vg, UI_FONT_NAME, fontPath) == -1) {
            throw new IllegalStateException("Unable to load a UI font for NanoVG");
        }

        uploadBuffer = BufferUtils.createByteBuffer(ATLAS_MAX_WIDTH * ATLAS_MAX_HEIGHT * 4);
        syncAtlasTexture();
    }

    private void initDialogs() {
        int result = NativeFileDialog.NFD_Init();
        if (result != NativeFileDialog.NFD_OKAY) {
            throw new IllegalStateException("Unable to initialize NFD: " + NativeFileDialog.NFD_GetError());
        }
        nfdInitialized = true;
    }

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            syncAtlasTexture();
            renderFrame();
            glfwSwapBuffers(window);
            executePendingAction();
        }
    }

    private void renderFrame() {
        float[] mouse = queryMousePosition();
        mouseX = mouse[0];
        mouseY = mouse[1];
        boolean leftDown = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
        boolean leftClicked = leftDown && !previousLeftDown;
        previousLeftDown = leftDown;

        int[] windowSize = queryWindowSize();
        int[] framebufferSize = queryFramebufferSize();
        float pixelRatio = framebufferSize[0] / (float) Math.max(windowSize[0], 1);

        glViewport(0, 0, framebufferSize[0], framebufferSize[1]);
        glClearColor(0.075f, 0.078f, 0.09f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);

        nvgBeginFrame(vg, windowSize[0], windowSize[1], pixelRatio);

        boolean clickConsumed = drawToolbar(leftClicked, windowSize[0]);
        AtlasViewport atlasViewport = drawAtlasPanel(windowSize[0], windowSize[1], leftClicked && !clickConsumed);
        drawFooter(windowSize[1], atlasViewport);

        nvgEndFrame(vg);
    }

    private boolean drawToolbar(boolean leftClicked, int windowWidth) {
        float panelX = PADDING;
        float panelY = PADDING;
        float panelWidth = windowWidth - PADDING * 2.0f;

        fillRoundedRect(panelX, panelY, panelWidth, TOOLBAR_HEIGHT - 12.0f, 16.0f, 0.14f, 0.16f, 0.18f, 0.95f);
        drawText(UI_FONT_NAME, 30.0f, panelX + 18.0f, panelY + 24.0f, "Atlas View", 0.98f, 0.98f, 0.98f, 1.0f);
        drawText(UI_FONT_NAME, 16.0f, panelX + 18.0f, panelY + 52.0f,
            "NanoVG UI, ImageIO texture loading, NFD file dialogs", 0.74f, 0.78f, 0.82f, 1.0f);

        float buttonY = panelY + 18.0f;
        float buttonX = panelX + 420.0f;

        if (drawButton("Load Texture", buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT, true, leftClicked)) {
            pendingAction = PendingAction.LOAD;
            return true;
        }
        buttonX += BUTTON_WIDTH + BUTTON_GAP;

        boolean hasSelection = selectedEntry != null;
        if (drawButton("Update Selected", buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT, hasSelection, leftClicked)) {
            pendingAction = PendingAction.UPDATE_SELECTED;
            return true;
        }
        buttonX += BUTTON_WIDTH + BUTTON_GAP;

        if (drawButton("Delete Selected", buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT, hasSelection, leftClicked)) {
            pendingAction = PendingAction.DELETE_SELECTED;
            return true;
        }
        buttonX += BUTTON_WIDTH + BUTTON_GAP;

        if (drawButton("Repack", buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT, !entries.isEmpty(), leftClicked)) {
            pendingAction = PendingAction.REPACK;
            return true;
        }

        return false;
    }

    private AtlasViewport drawAtlasPanel(int windowWidth, int windowHeight, boolean allowSelectionClick) {
        float panelX = PADDING;
        float panelY = TOOLBAR_HEIGHT + PADDING;
        float panelWidth = windowWidth - PADDING * 2.0f;
        float panelHeight = windowHeight - TOOLBAR_HEIGHT - 90.0f;

        fillRoundedRect(panelX, panelY, panelWidth, panelHeight, 20.0f, 0.12f, 0.13f, 0.15f, 0.96f);
        AtlasViewport viewport = computeAtlasViewport(panelX + 24.0f, panelY + 24.0f, panelWidth - 48.0f, panelHeight - 48.0f);

        drawCheckerboard(viewport);
        drawAtlasImage(viewport);
        drawFreeRegions(viewport);
        drawSelection(viewport);

        if (allowSelectionClick) {
            handleAtlasSelection(viewport);
        }

        drawText(UI_FONT_NAME, 18.0f, panelX + 24.0f, panelY + panelHeight - 16.0f,
            atlasSummary(), 0.83f, 0.86f, 0.90f, 1.0f);
        return viewport;
    }

    private void drawFooter(int windowHeight, AtlasViewport atlasViewport) {
        float footerY = windowHeight - 48.0f;
        drawText(UI_FONT_NAME, 17.0f, PADDING, footerY, statusMessage, 0.90f, 0.93f, 0.96f, 1.0f);

        if (selectedEntry != null) {
            AtlasTexture texture = selectedEntry.texture();
            String selected = "Selected: %s  [%d, %d]  %dx%d".formatted(
                selectedEntry.sourcePath().getFileName(), texture.x(), texture.y(), texture.width(), texture.height());
            drawText(UI_FONT_NAME, 16.0f, atlasViewport.x(), atlasViewport.y() - 12.0f,
                selected, 0.65f, 0.90f, 1.0f, 1.0f);
        }
    }

    private AtlasViewport computeAtlasViewport(float areaX, float areaY, float areaWidth, float areaHeight) {
        float scale = Math.min(areaWidth / atlas.width(), areaHeight / atlas.height());
        scale = Math.max(scale, 0.05f);
        float drawWidth = atlas.width() * scale;
        float drawHeight = atlas.height() * scale;
        float drawX = areaX + (areaWidth - drawWidth) * 0.5f;
        float drawY = areaY + (areaHeight - drawHeight) * 0.5f;
        return new AtlasViewport(drawX, drawY, drawWidth, drawHeight, scale);
    }

    private void drawCheckerboard(AtlasViewport viewport) {
        float tile = Math.max(10.0f, 16.0f * viewport.scale());
        for (float y = 0.0f; y < viewport.height(); y += tile) {
            for (float x = 0.0f; x < viewport.width(); x += tile) {
                boolean even = ((int) (x / tile) + (int) (y / tile)) % 2 == 0;
                float shade = even ? 0.28f : 0.18f;
                fillRect(viewport.x() + x, viewport.y() + y,
                    Math.min(tile, viewport.width() - x), Math.min(tile, viewport.height() - y),
                    shade, shade, shade, 1.0f);
            }
        }
        strokeRect(viewport.x(), viewport.y(), viewport.width(), viewport.height(), 2.0f, 0.45f, 0.48f, 0.52f, 1.0f);
    }

    private void drawAtlasImage(AtlasViewport viewport) {
        if (atlasImageId == 0) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            NVGPaint paint = NVGPaint.malloc(stack);
            nvgBeginPath(vg);
            nvgRect(vg, viewport.x(), viewport.y(), viewport.width(), viewport.height());
            nvgImagePattern(vg, viewport.x(), viewport.y(), viewport.width(), viewport.height(), 0.0f, atlasImageId, 1.0f, paint);
            nvgFillPaint(vg, paint);
            nvgFill(vg);
            nvgClosePath(vg);
        }
    }

    private void drawFreeRegions(AtlasViewport viewport) {
        for (DirtyRegion freeRegion : atlas.freeRegions()) {
            float x = viewport.x() + freeRegion.x() * viewport.scale();
            float y = viewport.y() + freeRegion.y() * viewport.scale();
            float width = freeRegion.width() * viewport.scale();
            float height = freeRegion.height() * viewport.scale();
            fillRect(x, y, width, height, 0.92f, 0.24f, 0.24f, 0.12f);
            strokeRect(x, y, width, height, 1.5f, 0.95f, 0.30f, 0.30f, 0.92f);
        }
    }

    private void drawSelection(AtlasViewport viewport) {
        if (selectedEntry == null) {
            return;
        }

        AtlasTexture texture = selectedEntry.texture();
        float x = viewport.x() + texture.x() * viewport.scale();
        float y = viewport.y() + texture.y() * viewport.scale();
        float width = texture.width() * viewport.scale();
        float height = texture.height() * viewport.scale();
        strokeRect(x, y, width, height, 3.0f, 0.32f, 0.92f, 1.0f, 1.0f);
    }

    private void handleAtlasSelection(AtlasViewport viewport) {
        if (!viewport.contains(mouseX, mouseY)) {
            selectedEntry = null;
            return;
        }

        int atlasX = (int) ((mouseX - viewport.x()) / viewport.scale());
        int atlasY = (int) ((mouseY - viewport.y()) / viewport.scale());

        selectedEntry = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            TextureEntry entry = entries.get(i);
            AtlasTexture texture = entry.texture();
            if (!texture.isAlive()) {
                continue;
            }
            if (atlasX >= texture.x() && atlasX < texture.x() + texture.width()
                && atlasY >= texture.y() && atlasY < texture.y() + texture.height()) {
                selectedEntry = entry;
                statusMessage = "Selected " + entry.sourcePath().getFileName();
                break;
            }
        }
    }

    private boolean drawButton(String label,
                               float x,
                               float y,
                               float width,
                               float height,
                               boolean enabled,
                               boolean leftClicked) {
        boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        float r = enabled ? (hovered ? 0.25f : 0.20f) : 0.12f;
        float g = enabled ? (hovered ? 0.31f : 0.24f) : 0.12f;
        float b = enabled ? (hovered ? 0.34f : 0.27f) : 0.12f;

        fillRoundedRect(x, y, width, height, 12.0f, r, g, b, 1.0f);
        strokeRect(x, y, width, height, 1.5f, 0.38f, 0.44f, 0.48f, 1.0f);
        drawCenteredText(label, x + width * 0.5f, y + height * 0.5f, enabled ? 0.98f : 0.55f);

        return enabled && hovered && leftClicked;
    }

    private void handleLoadTexture() {
        List<Path> paths = chooseImagePaths();
        if (paths.isEmpty()) {
            return;
        }

        int loadedCount = 0;
        String lastFailure = null;
        TextureEntry lastLoadedEntry = null;

        for (Path path : paths) {
            try {
                LoadedImage image = loadImage(path);
                AtlasTexture texture = atlas.addTexture(image.bytes(), image.width(), image.height());
                TextureEntry entry = new TextureEntry(texture, path);
                entries.add(entry);
                lastLoadedEntry = entry;
                loadedCount++;
            } catch (Exception exception) {
                lastFailure = path.getFileName() + ": " + exception.getMessage();
            }
        }

        if (lastLoadedEntry != null) {
            selectedEntry = lastLoadedEntry;
        }

        if (loadedCount == 0) {
            statusMessage = "Load failed: " + (lastFailure == null ? "No files loaded." : lastFailure);
        } else if (lastFailure == null) {
            statusMessage = loadedCount == 1
                ? "Loaded " + lastLoadedEntry.sourcePath().getFileName()
                : "Loaded " + loadedCount + " textures.";
        } else {
            statusMessage = "Loaded " + loadedCount + " textures, last error: " + lastFailure;
        }
    }

    private void handleUpdateSelectedTexture() {
        if (selectedEntry == null) {
            return;
        }

        Path path = chooseImagePath();
        if (path == null) {
            return;
        }

        try {
            LoadedImage image = loadImage(path);
            atlas.updateTexture(selectedEntry.texture(), image.bytes(), image.width(), image.height());
            selectedEntry.sourcePath(path);
            statusMessage = "Updated " + path.getFileName();
        } catch (Exception exception) {
            statusMessage = "Update failed: " + exception.getMessage();
        }
    }

    private void handleDeleteSelectedTexture() {
        if (selectedEntry == null) {
            return;
        }

        String name = selectedEntry.sourcePath().getFileName().toString();
        atlas.removeTexture(selectedEntry.texture());
        entries.remove(selectedEntry);
        selectedEntry = null;
        statusMessage = "Deleted " + name;
    }

    private Path chooseImagePath() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer outPath = stack.mallocPointer(1);
            NFDFilterItem.Buffer filters = NFDFilterItem.calloc(1, stack);
            filters.get(0).set(stack.UTF8("Images"), stack.UTF8("png,jpg,jpeg,bmp,gif,wbmp"));

            int result = NativeFileDialog.NFD_OpenDialog(outPath, filters,
                lastDialogDirectory == null ? null : lastDialogDirectory.toString());

            if (result == NativeFileDialog.NFD_CANCEL) {
                statusMessage = "Dialog canceled.";
                return null;
            }
            if (result != NativeFileDialog.NFD_OKAY) {
                throw new IllegalStateException(String.valueOf(NativeFileDialog.NFD_GetError()));
            }

            long pathPointer = outPath.get(0);
            String pathValue = memUTF8(pathPointer);
            NativeFileDialog.NFD_FreePath(pathPointer);

            Path path = Path.of(pathValue);
            lastDialogDirectory = path.getParent();
            return path;
        }
    }

    private List<Path> chooseImagePaths() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer outPaths = stack.mallocPointer(1);
            NFDFilterItem.Buffer filters = NFDFilterItem.calloc(1, stack);
            filters.get(0).set(stack.UTF8("Images"), stack.UTF8("png,jpg,jpeg,bmp,gif,wbmp"));

            int result = NativeFileDialog.NFD_OpenDialogMultiple(outPaths, filters,
                lastDialogDirectory == null ? null : lastDialogDirectory.toString());

            if (result == NativeFileDialog.NFD_CANCEL) {
                statusMessage = "Dialog canceled.";
                return List.of();
            }
            if (result != NativeFileDialog.NFD_OKAY) {
                throw new IllegalStateException(String.valueOf(NativeFileDialog.NFD_GetError()));
            }

            long pathSet = outPaths.get(0);
            try {
                int[] count = {0};
                int countResult = NativeFileDialog.NFD_PathSet_GetCount(pathSet, count);
                if (countResult != NativeFileDialog.NFD_OKAY) {
                    throw new IllegalStateException(String.valueOf(NativeFileDialog.NFD_GetError()));
                }

                ArrayList<Path> paths = new ArrayList<>(count[0]);
                PointerBuffer outPath = stack.mallocPointer(1);
                for (int i = 0; i < count[0]; i++) {
                    int pathResult = NativeFileDialog.NFD_PathSet_GetPath(pathSet, i, outPath);
                    if (pathResult != NativeFileDialog.NFD_OKAY) {
                        throw new IllegalStateException(String.valueOf(NativeFileDialog.NFD_GetError()));
                    }

                    long pathPointer = outPath.get(0);
                    String pathValue = memUTF8(pathPointer);
                    NativeFileDialog.NFD_PathSet_FreePath(pathPointer);
                    Path path = Path.of(pathValue);
                    paths.add(path);
                    lastDialogDirectory = path.getParent();
                }
                return paths;
            } finally {
                NativeFileDialog.NFD_PathSet_Free(pathSet);
            }
        }
    }

    private LoadedImage loadImage(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("File does not exist: " + path);
        }
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IOException("ImageIO could not decode: " + path.getFileName());
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        byte[] rgba = new byte[width * height * 4];

        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            int offset = i * 4;
            rgba[offset] = (byte) ((pixel >>> 16) & 0xff);
            rgba[offset + 1] = (byte) ((pixel >>> 8) & 0xff);
            rgba[offset + 2] = (byte) (pixel & 0xff);
            rgba[offset + 3] = (byte) ((pixel >>> 24) & 0xff);
        }
        return new LoadedImage(rgba, width, height);
    }

    private void syncAtlasTexture() {
        int atlasByteCount = atlas.width() * atlas.height() * atlas.format().bytesPerPixel();
        boolean sizeChanged = uploadedAtlasHeight != atlas.height();
        if (!sizeChanged && !atlas.isDirty() && atlasImageId != 0) {
            return;
        }

        uploadBuffer.clear();
        uploadBuffer.put(atlas.pixels(), 0, atlasByteCount);
        uploadBuffer.flip();

        if (sizeChanged || atlasImageId == 0) {
            uploadedAtlasHeight = atlas.height();
            recreateNanoVGImage();
        } else {
            nvgUpdateImage(vg, atlasImageId, uploadBuffer);
        }

        atlas.clearDirty();
    }

    private void recreateNanoVGImage() {
        if (atlasImageId != 0) {
            nvgDeleteImage(vg, atlasImageId);
        }
        atlasImageId = nvgCreateImageRGBA(vg, atlas.width(), atlas.height(), 0, uploadBuffer);
        if (atlasImageId == 0) {
            throw new IllegalStateException("Unable to create NanoVG image from atlas pixels");
        }
    }

    private void executePendingAction() {
        PendingAction action = pendingAction;
        pendingAction = PendingAction.NONE;

        switch (action) {
            case NONE -> {
            }
            case LOAD -> handleLoadTexture();
            case UPDATE_SELECTED -> handleUpdateSelectedTexture();
            case DELETE_SELECTED -> handleDeleteSelectedTexture();
            case REPACK -> {
                atlas.repack();
                statusMessage = "Atlas repacked.";
            }
        }
    }

    private String atlasSummary() {
        int activeTextures = 0;
        for (TextureEntry entry : entries) {
            if (entry.texture().isAlive()) {
                activeTextures++;
            }
        }
        return "Atlas %dx%d / max %dx%d  |  textures: %d  |  free rects: %d".formatted(
            atlas.width(), atlas.height(), atlas.maxWidth(), atlas.maxHeight(), activeTextures, atlas.freeRegions().length);
    }

    private int[] queryWindowSize() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            glfwGetWindowSize(window, width, height);
            return new int[]{Math.max(width.get(0), 1), Math.max(height.get(0), 1)};
        }
    }

    private int[] queryFramebufferSize() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            glfwGetFramebufferSize(window, width, height);
            return new int[]{Math.max(width.get(0), 1), Math.max(height.get(0), 1)};
        }
    }

    private float[] queryMousePosition() {
        try (MemoryStack stack = stackPush()) {
            var x = stack.mallocDouble(1);
            var y = stack.mallocDouble(1);
            glfwGetCursorPos(window, x, y);
            return new float[]{(float) x.get(0), (float) y.get(0)};
        }
    }

    private static String findUiFontPath() {
        String[] candidates = {
            "C:/Windows/Fonts/segoeui.ttf",
            "C:/Windows/Fonts/arial.ttf",
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf"
        };
        for (String candidate : candidates) {
            if (Files.isRegularFile(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private void fillRect(float x, float y, float width, float height, float r, float g, float b, float a) {
        try (MemoryStack stack = stackPush()) {
            NVGColor color = NVGColor.malloc(stack);
            nvgRGBAf(r, g, b, a, color);
            nvgBeginPath(vg);
            nvgRect(vg, x, y, width, height);
            nvgFillColor(vg, color);
            nvgFill(vg);
            nvgClosePath(vg);
        }
    }

    private void fillRoundedRect(float x,
                                 float y,
                                 float width,
                                 float height,
                                 float radius,
                                 float r,
                                 float g,
                                 float b,
                                 float a) {
        try (MemoryStack stack = stackPush()) {
            NVGColor color = NVGColor.malloc(stack);
            nvgRGBAf(r, g, b, a, color);
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, width, height, radius);
            nvgFillColor(vg, color);
            nvgFill(vg);
            nvgClosePath(vg);
        }
    }

    private void strokeRect(float x, float y, float width, float height, float strokeWidth, float r, float g, float b, float a) {
        try (MemoryStack stack = stackPush()) {
            NVGColor color = NVGColor.malloc(stack);
            nvgRGBAf(r, g, b, a, color);
            nvgBeginPath(vg);
            nvgRect(vg, x, y, width, height);
            nvgStrokeWidth(vg, strokeWidth);
            nvgStrokeColor(vg, color);
            nvgStroke(vg);
            nvgClosePath(vg);
        }
    }

    private void drawText(String font, float size, float x, float y, String text, float r, float g, float b, float a) {
        try (MemoryStack stack = stackPush()) {
            NVGColor color = NVGColor.malloc(stack);
            nvgRGBAf(r, g, b, a, color);
            nvgFontFace(vg, font);
            nvgFontSize(vg, size);
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, color);
            nvgText(vg, x, y, text);
        }
    }

    private void drawCenteredText(String text, float centerX, float centerY, float brightness) {
        try (MemoryStack stack = stackPush()) {
            NVGColor color = NVGColor.malloc(stack);
            nvgRGBAf(brightness, brightness, brightness, 1.0f, color);
            nvgFontFace(vg, UI_FONT_NAME);
            nvgFontSize(vg, 17.0f);
            nvgTextAlign(vg, org.lwjgl.nanovg.NanoVG.NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, color);
            nvgText(vg, centerX, centerY, text);
        }
    }

    private void shutdown() {
        if (atlasImageId != 0 && vg != NULL) {
            nvgDeleteImage(vg, atlasImageId);
        }
        if (vg != NULL) {
            nvgDelete(vg);
        }
        if (nfdInitialized) {
            NativeFileDialog.NFD_Quit();
        }
        if (window != NULL) {
            glfwDestroyWindow(window);
        }
        glfwTerminate();
        if (errorCallback != null) {
            errorCallback.free();
        }
    }

    private record LoadedImage(byte[] bytes, int width, int height) {
    }

    private record AtlasViewport(float x, float y, float width, float height, float scale) {
        private boolean contains(float pointX, float pointY) {
            return pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height;
        }
    }

    private static final class TextureEntry {

        private final AtlasTexture texture;
        private Path sourcePath;

        private TextureEntry(AtlasTexture texture, Path sourcePath) {
            this.texture = texture;
            this.sourcePath = sourcePath;
        }

        private AtlasTexture texture() {
            return texture;
        }

        private Path sourcePath() {
            return sourcePath;
        }

        private void sourcePath(Path sourcePath) {
            this.sourcePath = sourcePath;
        }
    }

    private enum PendingAction {
        NONE,
        LOAD,
        UPDATE_SELECTED,
        DELETE_SELECTED,
        REPACK
    }
}

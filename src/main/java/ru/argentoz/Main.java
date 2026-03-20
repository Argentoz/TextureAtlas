package ru.argentoz;

public class Main {
    public static void main(String[] args) {
        TextureAtlas atlas = new TextureAtlas(AtlasFormat.RGBA8, 256, 256);

        byte[] iconPixels = new byte[16 * 16 * atlas.format().bytesPerPixel()];
        AtlasTexture icon = atlas.addTexture(iconPixels, 16, 16);

        byte[] updatedIconPixels = new byte[16 * 16 * atlas.format().bytesPerPixel()];
        atlas.updateTexture(icon, updatedIconPixels, 16, 16);

        atlas.repack();

        System.out.println("Atlas: " + atlas.width() + "x" + atlas.height());
        System.out.println("Icon UV: " + icon.u0() + ", " + icon.v0() + " -> " + icon.u1() + ", " + icon.v1());

        for (DirtyRegion region : atlas.consumeDirtyRegions()) {
            System.out.println("Dirty: " + region);
        }
    }
}

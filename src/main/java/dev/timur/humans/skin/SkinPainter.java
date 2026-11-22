package dev.timur.humans.skin;

import dev.timur.humans.Expression;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Paints classic (4px-arm) 64x64 Minecraft skins. The body is identical across
 * expressions (same seeded noise); only the 8x8 face changes.
 */
public final class SkinPainter {
    private static final int FX = 8, FY = 8; // head front origin

    private SkinPainter() {}

    public static BufferedImage paint(Look look, Expression expr) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Random rnd = new Random(look.id().hashCode() * 31L + 7);
        paintBase(img, look, rnd);
        paintFace(img, look, expr);
        return img;
    }

    // ---------------------------------------------------------------- base

    private static void paintBase(BufferedImage img, Look l, Random r) {
        // Head: all six faces skin
        fill(img, 8, 0, 16, 8, l.skin(), r);   // top + bottom
        fill(img, 0, 8, 32, 8, l.skin(), r);   // right, front, left, back
        // Hair
        int backRows = switch (l.hairStyle()) { case Look.LONG, Look.PONYTAIL -> 8; case Look.BUZZ -> 2; case Look.CURLY -> 5; default -> 3; };
        int sideRows = switch (l.hairStyle()) { case Look.LONG -> 7; case Look.BUZZ -> 1; case Look.CURLY, Look.PONYTAIL -> 3; default -> 2; };
        fill(img, 8, 0, 8, 8, l.hair(), r);                 // top of head
        fill(img, 24, 8, 8, backRows, l.hair(), r);         // back
        fill(img, 0, 8, 8, sideRows, l.hair(), r);          // right side
        fill(img, 16, 8, 8, sideRows, l.hair(), r);         // left side
        // side hair hugging the back edge of the head
        fill(img, 0, 8, 2, Math.min(8, sideRows + 2), l.hair(), r);
        fill(img, 22, 8, 2, Math.min(8, sideRows + 2), l.hair(), r);
        if (l.hairStyle() == Look.PONYTAIL) fill(img, 27, 12, 2, 4, darker(l.hair(), 0.8f), r);
        // front hairline
        fill(img, FX, FY, 8, 1, l.hair(), r);
        switch (l.hairStyle()) {
            case Look.SHORT -> { set(img, FX, FY + 1, l.hair()); set(img, FX + 1, FY + 1, l.hair()); set(img, FX + 6, FY + 1, l.hair()); set(img, FX + 7, FY + 1, l.hair()); }
            case Look.LONG -> { fill(img, FX, FY + 1, 8, 1, l.hair(), r); fill(img, FX, FY + 2, 1, 6, l.hair(), r); fill(img, FX + 7, FY + 2, 1, 6, l.hair(), r); }
            case Look.PONYTAIL -> { fill(img, FX, FY + 1, 3, 1, l.hair(), r); set(img, FX + 7, FY + 1, l.hair()); }
            case Look.CURLY -> { fill(img, FX, FY + 1, 8, 1, l.hair(), r); set(img, FX, FY + 2, l.hair()); set(img, FX + 7, FY + 2, l.hair()); }
            default -> {}
        }
        if (l.beard()) {
            fill(img, FX + 1, FY + 6, 6, 2, darker(l.hair(), 0.95f), r);
            fill(img, 16, 0, 8, 8, darker(l.hair(), 0.95f), r); // under-chin
            fill(img, 0, 13, 2, 3, l.hair(), r); fill(img, 22, 13, 2, 3, l.hair(), r);
        }

        // Body (shirt)
        fill(img, 16, 16, 24, 16, l.shirt(), r);
        clear(img, 16, 16, 4, 4); clear(img, 36, 16, 4, 4); // unused corners of body block
        fill(img, 20, 31, 8, 1, l.shirtAccent(), r);    // belt line front
        fill(img, 32, 31, 8, 1, l.shirtAccent(), r);    // belt line back
        set(img, 23, 20, l.skin()); set(img, 24, 20, l.skin()); // collar

        // Right arm (40,16): skin with sleeves
        fill(img, 44, 16, 8, 4, l.skin(), r);
        fill(img, 40, 20, 16, 12, l.skin(), r);
        fill(img, 44, 16, 4, 4, l.shirt(), r);
        fill(img, 40, 20, 16, 5, l.shirt(), r);
        fill(img, 40, 24, 16, 1, l.shirtAccent(), r);
        // Left arm (32,48)
        fill(img, 36, 48, 8, 4, l.skin(), r);
        fill(img, 32, 52, 16, 12, l.skin(), r);
        fill(img, 36, 48, 4, 4, l.shirt(), r);
        fill(img, 32, 52, 16, 5, l.shirt(), r);
        fill(img, 32, 56, 16, 1, l.shirtAccent(), r);

        // Right leg (0,16)
        fill(img, 4, 16, 8, 4, l.pants(), r);
        fill(img, 0, 20, 16, 12, l.pants(), r);
        fill(img, 8, 16, 4, 4, l.shoes(), r);
        fill(img, 0, 29, 16, 3, l.shoes(), r);
        // Left leg (16,48)
        fill(img, 20, 48, 8, 4, l.pants(), r);
        fill(img, 16, 52, 16, 12, l.pants(), r);
        fill(img, 24, 48, 4, 4, l.shoes(), r);
        fill(img, 16, 61, 16, 3, l.shoes(), r);
    }

    // ---------------------------------------------------------------- face

    private static void paintFace(BufferedImage img, Look l, Expression e) {
        Color white = new Color(0xF4F4F4), pupil = l.eyes(), brow = darker(l.hair(), 0.85f);
        Color mouth = darker(l.skin(), 0.45f), lip = darker(l.skin(), 0.75f), nose = darker(l.skin(), 0.9f);
        Color blush = blend(l.skin(), new Color(0xE0605A), 0.35f), tear = new Color(0x7EC8F0);
        Color lid = darker(l.skin(), 0.62f);

        // nose (all expressions)
        f(img, 3, 5, nose); f(img, 4, 5, nose);

        switch (e) {
            case NEUTRAL -> { browsFlat(img, brow); eyes(img, white, pupil); f(img, 2, 6, lip); f(img, 3, 6, mouth); f(img, 4, 6, mouth); f(img, 5, 6, lip); }
            case HAPPY -> {
                browsFlat(img, brow); eyes(img, white, pupil);
                f(img, 1, 5, blush); f(img, 6, 5, blush);
                f(img, 2, 6, mouth); f(img, 3, 6, white); f(img, 4, 6, white); f(img, 5, 6, mouth);
                f(img, 3, 7, mouth); f(img, 4, 7, mouth);
            }
            case SAD -> {
                f(img, 2, 2, brow); f(img, 5, 2, brow); f(img, 1, 3, brow); f(img, 6, 3, brow);
                eyes(img, white, pupil); f(img, 1, 5, tear); f(img, 1, 6, tear);
                f(img, 3, 6, mouth); f(img, 4, 6, mouth); f(img, 2, 7, mouth); f(img, 5, 7, mouth);
            }
            case ANGRY -> {
                f(img, 1, 2, brow); f(img, 6, 2, brow); f(img, 2, 3, brow); f(img, 5, 3, brow);
                f(img, 1, 4, white); f(img, 2, 4, new Color(0x8A1C1C)); f(img, 5, 4, new Color(0x8A1C1C)); f(img, 6, 4, white);
                f(img, 1, 5, blush); f(img, 6, 5, blush);
                for (int x = 2; x <= 5; x++) f(img, x, 6, mouth);
                f(img, 3, 7, white); f(img, 4, 7, white);
            }
            case SCARED -> {
                f(img, 1, 2, brow); f(img, 2, 2, brow); f(img, 5, 2, brow); f(img, 6, 2, brow);
                f(img, 1, 3, white); f(img, 2, 3, white); f(img, 5, 3, white); f(img, 6, 3, white);
                f(img, 1, 4, white); f(img, 2, 4, pupil); f(img, 5, 4, pupil); f(img, 6, 4, white);
                f(img, 7, 3, tear);
                f(img, 2, 7, mouth); f(img, 3, 6, mouth); f(img, 4, 7, mouth); f(img, 5, 6, mouth);
            }
            case SURPRISED -> {
                f(img, 1, 2, brow); f(img, 2, 2, brow); f(img, 5, 2, brow); f(img, 6, 2, brow);
                f(img, 1, 3, white); f(img, 2, 3, white); f(img, 5, 3, white); f(img, 6, 3, white);
                f(img, 1, 4, white); f(img, 2, 4, pupil); f(img, 5, 4, pupil); f(img, 6, 4, white);
                f(img, 3, 6, new Color(0x1A1010)); f(img, 4, 6, new Color(0x1A1010));
                f(img, 3, 7, new Color(0x1A1010)); f(img, 4, 7, new Color(0x1A1010));
            }
            case HURT -> {
                f(img, 2, 3, brow); f(img, 5, 3, brow);
                f(img, 1, 4, lid); f(img, 2, 4, lid); f(img, 5, 4, lid); f(img, 6, 4, lid);
                f(img, 1, 5, blush); f(img, 6, 5, blush);
                for (int x = 2; x <= 5; x++) { f(img, x, 6, mouth); f(img, x, 7, mouth); }
                f(img, 3, 6, white); f(img, 4, 6, white);
            }
            case BLINK -> { browsFlat(img, brow); closedEyes(img, lid); f(img, 3, 6, mouth); f(img, 4, 6, mouth); f(img, 2, 6, lip); f(img, 5, 6, lip); }
            case SLEEPING -> { f(img, 1, 3, brow); f(img, 2, 3, brow); f(img, 5, 3, brow); f(img, 6, 3, brow); closedEyes(img, lid); f(img, 3, 6, mouth); f(img, 4, 6, lip); }
        }
    }

    private static void browsFlat(BufferedImage img, Color brow) { f(img, 1, 3, brow); f(img, 2, 3, brow); f(img, 5, 3, brow); f(img, 6, 3, brow); }
    private static void eyes(BufferedImage img, Color white, Color pupil) { f(img, 1, 4, white); f(img, 2, 4, pupil); f(img, 5, 4, pupil); f(img, 6, 4, white); }
    private static void closedEyes(BufferedImage img, Color lid) { f(img, 1, 4, lid); f(img, 2, 4, lid); f(img, 5, 4, lid); f(img, 6, 4, lid); }

    // ---------------------------------------------------------------- pixels

    private static void f(BufferedImage img, int col, int row, Color c) { set(img, FX + col, FY + row, c); }

    private static void set(BufferedImage img, int x, int y, Color c) { img.setRGB(x, y, c.getRGB() | 0xFF000000); }

    private static void clear(BufferedImage img, int x, int y, int w, int h) {
        for (int i = x; i < x + w; i++) for (int j = y; j < y + h; j++) img.setRGB(i, j, 0);
    }

    private static void fill(BufferedImage img, int x, int y, int w, int h, Color c, Random r) {
        for (int i = x; i < x + w; i++)
            for (int j = y; j < y + h; j++) {
                float k = 1f + (r.nextFloat() - 0.5f) * 0.09f;
                set(img, i, j, darker(c, k));
            }
    }

    static Color darker(Color c, float k) {
        return new Color(clamp(c.getRed() * k), clamp(c.getGreen() * k), clamp(c.getBlue() * k));
    }

    static Color blend(Color a, Color b, float t) {
        return new Color(clamp(a.getRed() * (1 - t) + b.getRed() * t), clamp(a.getGreen() * (1 - t) + b.getGreen() * t), clamp(a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    private static int clamp(float v) { return Math.max(0, Math.min(255, Math.round(v))); }
}

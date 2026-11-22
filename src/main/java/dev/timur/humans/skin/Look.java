package dev.timur.humans.skin;

import java.awt.Color;
import java.util.List;
import java.util.Random;

/** A human's appearance: palette + hairstyle. Skins are painted from this at startup. */
public record Look(String id, Color skin, Color hair, Color eyes, Color shirt, Color shirtAccent,
                   Color pants, Color shoes, int hairStyle, boolean beard) {

    public static final int SHORT = 0, LONG = 1, BUZZ = 2, PONYTAIL = 3, CURLY = 4;

    private static Color c(int rgb) { return new Color(rgb); }

    public static final List<Look> ALL = List.of(
        new Look("ash",    c(0xE0AC88), c(0x3B2A1E), c(0x2F5D8C), c(0x3E7CB1), c(0x2E5E88), c(0x2B2B3A), c(0x4A3526), SHORT, false),
        new Look("maple",  c(0xF1C7A5), c(0xB5531E), c(0x3C7A3C), c(0x7A9E3B), c(0x5C7A2B), c(0x5A4632), c(0x2E2E2E), LONG, false),
        new Look("cedar",  c(0x8D5A3B), c(0x141414), c(0x3A2414), c(0xC23B3B), c(0x8E2A2A), c(0x26344F), c(0x1C1C1C), BUZZ, true),
        new Look("willow", c(0xF3D2B5), c(0xE8C66A), c(0x3D6FB0), c(0xE58FB5), c(0xC46A92), c(0x5A6FA0), c(0xEDEDED), PONYTAIL, false),
        new Look("oak",    c(0xC68A61), c(0x5A3A22), c(0x4B3321), c(0x9C6B3C), c(0x7A512B), c(0x3C3C3C), c(0x2A1E14), SHORT, true),
        new Look("birch",  c(0xE9B997), c(0x9A9A9A), c(0x506070), c(0x6B5B95), c(0x50437A), c(0x2F2F2F), c(0x3B2B1E), SHORT, true),
        new Look("ember",  c(0x6B4128), c(0x1E1410), c(0x2A1A10), c(0xF0A030), c(0xC07A18), c(0x34302A), c(0x5A2E14), CURLY, false),
        new Look("frost",  c(0xF6DCC8), c(0x1D1D2B), c(0x5A8FC0), c(0x3FB5A5), c(0x2E8A7E), c(0x1F2933), c(0x111111), LONG, false),
        new Look("moss",   c(0xD49A74), c(0x6B4A2B), c(0x5C7A3A), c(0x4F6B35), c(0x3B5227), c(0x6B5A44), c(0x3A2A1C), CURLY, false),
        new Look("slate",  c(0xA56F4D), c(0x2B2B2B), c(0x352418), c(0x5E6B78), c(0x46515C), c(0x23262B), c(0x5C4632), PONYTAIL, false)
    );

    public static Look byId(String id) {
        for (Look l : ALL) if (l.id.equals(id)) return l;
        return ALL.getFirst();
    }

    public static Look random(Random r) { return ALL.get(r.nextInt(ALL.size())); }
}

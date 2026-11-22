package dev.timur.humans.skin;

import com.sun.net.httpserver.HttpServer;
import dev.timur.humans.Expression;
import dev.timur.humans.HumansPlugin;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the face-expression resource pack in memory, serves it over HTTP and sends it to joining players. */
public final class PackServer implements Listener {
    private static final UUID PACK_ID = UUID.fromString("6d1c2b6e-5a7f-4b0e-9b1d-48a3f1e0c0de");
    private final HumansPlugin plugin;
    private byte[] zip;
    private String sha1;
    private HttpServer http;

    public PackServer(HumansPlugin plugin) { this.plugin = plugin; }

    public void buildAndStart() {
        try {
            zip = buildZip();
            sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip));
            int port = plugin.getConfig().getInt("pack.port", 8080);
            http = HttpServer.create(new InetSocketAddress(port), 0);
            http.createContext("/humans-pack.zip", ex -> {
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                ex.sendResponseHeaders(200, zip.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(zip); }
            });
            http.start();
            plugin.getLogger().info("Face pack: " + zip.length + " bytes, sha1 " + sha1 + ", serving on port " + port);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not build/serve the face pack", e);
        }
    }

    public void stop() { if (http != null) http.stop(0); }

    private byte[] buildZip() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            put(z, "pack.mcmeta", ("{\"pack\":{\"description\":\"AI Humans - faces\",\"min_format\":[97,0],\"max_format\":[97,1]}}").getBytes(StandardCharsets.UTF_8));
            for (Look look : Look.ALL) {
                for (Expression e : Expression.values()) {
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    ImageIO.write(SkinPainter.paint(look, e), "png", png);
                    put(z, "assets/humans/textures/skin/" + look.id() + "_" + e.id() + ".png", png.toByteArray());
                }
            }
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0); // deterministic zip -> stable hash
        z.putNextEntry(entry);
        z.write(data);
        z.closeEntry();
    }

    public void send(Player p) {
        if (zip == null) return;
        String url = plugin.getConfig().getString("pack.public_url", "") + "?v=" + sha1.substring(0, 10);
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url), sha1);
        p.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
            .packs(info)
            .required(plugin.getConfig().getBoolean("pack.required", true))
            .prompt(Component.text(plugin.getConfig().getString("pack.prompt", "")))
            .replace(false)
            .build());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { send(e.getPlayer()); }
}

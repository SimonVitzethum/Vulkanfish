package simon.vulkanfish.client.gpu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Laedt vorkompilierte SPIR-V-Module (aus Slang gebaut, siehe compileSlang-
 * Task) aus {@code assets/vulkanfish/shaders/spv/} und das
 * {@code shaders.json}-Manifest. Die eigentliche VkShaderModule-Erzeugung
 * passiert im Renderer ueber Blaze3D-Vulkan-Handles (MC 26.2 bringt
 * nativ Vulkan mit, kein VulkanMod noetig).
 */
public final class SlangShaderLoader {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private static final String BASE = "/assets/vulkanfish/shaders/";
    private final Map<String, byte[]> spirv = new LinkedHashMap<>();
    private final Map<String, String> stages = new LinkedHashMap<>();

    public void load() throws IOException {
        String manifest = readText(BASE + "slang/shaders.json");
        JsonObject root = JsonParser.parseString(manifest).getAsJsonObject();
        JsonArray modules = root.getAsJsonArray("modules");
        for (JsonElement e : modules) {
            JsonObject m = e.getAsJsonObject();
            String spv = m.get("spv").getAsString();
            String entry = m.get("entry").getAsString();
            String stage = m.get("stage").getAsString();
            byte[] bytes = readBytes(BASE + "spv/" + spv);
            if ((bytes.length & 3) != 0 || bytes.length < 20) {
                throw new IOException("Ungueltiges SPIR-V: " + spv);
            }
            // Magic prüfen: 0x07230203 little endian
            if (!(bytes[0] == 0x03 && bytes[1] == 0x02 && bytes[2] == 0x23 && bytes[3] == 0x07)) {
                throw new IOException("SPIR-V Magic fehlt: " + spv);
            }
            spirv.put(entry, bytes);
            stages.put(entry, stage);
            LOG.info("[vulkanfish] SPIR-V geladen: {} ({} {})", spv, stage, entry);
        }
    }

    public ByteBuffer get(String entry) {
        byte[] b = spirv.get(entry);
        if (b == null) throw new IllegalArgumentException("Unbekannter Entry: " + entry);
        return ByteBuffer.allocateDirect(b.length).put(b).flip();
    }

    public String stageOf(String entry) {
        return stages.get(entry);
    }

    public int moduleCount() {
        return spirv.size();
    }

    private static String readText(String path) throws IOException {
        try (InputStream in = SlangShaderLoader.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Fehlt: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readBytes(String path) throws IOException {
        try (InputStream in = SlangShaderLoader.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Fehlt: " + path);
            return in.readAllBytes();
        }
    }
}

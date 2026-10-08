package com.replaymod.core.versions;

import com.google.gson.Gson;
import com.replaymod.core.ReplayMod;
import net.minecraft.SharedConstants;
import net.minecraft.resource.AbstractFileResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.neoforged.fml.ModList;
import net.neoforged.fml.jarcontents.JarContents;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static de.johni0702.minecraft.gui.versions.MCVer.identifier;

/**
 * Resource pack which on-the-fly converts pre-1.13 language files into 1.13 json format.
 * Also duplicates {@code replaymod.input.*} bindings to {@code key.replaymod.*} as convention.
 */
public class LangResourcePack extends AbstractFileResourcePack {
    private static final Gson GSON = new Gson();
    public static final String NAME = "replaymod_lang";
    private static final Pattern JSON_FILE_PATTERN = Pattern.compile("^assets/" + ReplayMod.MOD_ID + "/lang/([a-z][a-z])_([a-z][a-z]).json$");
    private static final Pattern LANG_FILE_NAME_PATTERN = Pattern.compile("^([a-zA-Z]{2})_([a-zA-Z]{2}).lang$");

    public static final String LEGACY_KEY_PREFIX = "replaymod.input.";
    private static final String FABRIC_KEY_FORMAT = "key." + ReplayMod.MOD_ID + ".%s";

    private static final String LANG_DIR = "assets/" + ReplayMod.MOD_ID + "/lang";

    // Read through FML's view of the mod file: its content roots are the jar files themselves in production,
    // so resolving paths below them does not reach the jar's entries.
    private final JarContents contents;

    public LangResourcePack() {
        super(new ResourcePackInfo(NAME, Text.literal("ReplayMod Translations"), ResourcePackSource.NONE, Optional.empty()));
        this.contents = ModList.get().getModFileById(ReplayMod.MOD_ID).getFile().getContents();
    }

    private String langFile(String path) {
        Matcher matcher = JSON_FILE_PATTERN.matcher(path);
        if (!matcher.matches()) return null;
        return String.format("%s/%s_%s.lang", LANG_DIR, matcher.group(1), matcher.group(2).toUpperCase());
    }

    @Override
    public net.minecraft.resource.InputSupplier<InputStream> openRoot(String... segments) {
        byte[] bytes;
        try {
            bytes = readFile(String.join("/", segments));
        } catch (IOException e) {
            return null;
        }
        if (bytes == null) {
            return null;
        }
        return () -> new ByteArrayInputStream(bytes);
    }

    @Override
    public net.minecraft.resource.InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        return openRoot(type.getDirectory(), id.getNamespace(), id.getPath());
    }

    private byte[] readFile(String path) throws IOException {
        if ("pack.mcmeta".equals(path)) {
            // 1.21.9+ pack metadata declares a supported format range instead of a single pack_format
            int packFormat = SharedConstants.getGameVersion().packVersion(ResourceType.CLIENT_RESOURCES).major();
            return ("{\"pack\": {\"description\": \"ReplayMod language files\", \"min_format\": "
                    + packFormat + ", \"max_format\": " + packFormat + "}}").getBytes(StandardCharsets.UTF_8);
        }

        String langFile = langFile(path);
        if (langFile == null || !contents.containsFile(langFile)) return null;

        List<String> langLines;
        try (InputStream in = contents.openFile(langFile)) {
            langLines = IOUtils.readLines(in, StandardCharsets.UTF_8);
        }

        Map<String, String> properties = new HashMap<>();
        for (String line : langLines) {
            if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
            int i = line.indexOf('=');
            String key = line.substring(0, i);
            String value = line.substring(i + 1);
            if (key.startsWith(LEGACY_KEY_PREFIX)) {
                properties.put(key, value);
                key = String.format(FABRIC_KEY_FORMAT, key.substring(LEGACY_KEY_PREFIX.length()));
            }
            properties.put(key, value);
        }

        return GSON.toJson(properties).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void findResources(ResourceType type, String namespace, String prefix, ResultConsumer consumer) {
        findResources(type, prefix, id -> consumer.accept(id, () -> {
            try {
                byte[] bytes = readFile(type.getDirectory() + "/" + id.getNamespace() + "/" + id.getPath());
                if (bytes == null) throw new IOException("Resource not found: " + id);
                return new ByteArrayInputStream(bytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
    }

    private void findResources(ResourceType type, String path, Consumer<Identifier> consumer) {
        if (type != ResourceType.CLIENT_RESOURCES) return;
        if (!"lang".equals(path)) return;
        contents.visitContent(LANG_DIR, (relativePath, resource) -> {
            Matcher matcher = LANG_FILE_NAME_PATTERN.matcher(relativePath.substring(relativePath.lastIndexOf('/') + 1));
            if (matcher.matches()) {
                String name = String.format("%s_%s.json", matcher.group(1).toLowerCase(), matcher.group(2).toLowerCase());
                consumer.accept(identifier(ReplayMod.MOD_ID, "lang/" + name));
            }
        });
    }

    @Override
    public Set<String> getNamespaces(ResourceType resourcePackType) {
        if (resourcePackType == ResourceType.CLIENT_RESOURCES) {
            return Collections.singleton("replaymod");
        } else {
            return Collections.emptySet();
        }
    }

    @Override
    public void close() {}
}

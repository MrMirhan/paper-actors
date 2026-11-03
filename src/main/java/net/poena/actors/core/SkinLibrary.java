package net.poena.actors.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Signed skin textures kept as {@code skins/<name>.json} ({"value": ..., "signature": ...}). The plugin never calls a skin service itself. */
public final class SkinLibrary {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private SkinLibrary() {
    }

    public static JsonObject load(Path dir, String name) {
        if (!NAME.matcher(name).matches() || name.contains("..")) {
            throw new ApiException(400, "skin name may only use letters, digits, . _ -");
        }
        Path file = dir.resolve(name + ".json").normalize();
        if (!file.startsWith(dir.normalize()) || !Files.isRegularFile(file)) {
            throw new ApiException(404, "no skin '" + name + "' in the skin library");
        }
        try {
            JsonObject o = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (o == null || !o.has("value")) {
                throw new ApiException(400, "skin '" + name + "' has no value");
            }
            return o;
        } catch (IOException | JsonParseException e) {
            throw new ApiException(400, "skin '" + name + "' could not be read");
        }
    }
}

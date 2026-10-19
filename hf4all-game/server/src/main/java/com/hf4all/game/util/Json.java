package com.hf4all.game.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The one Gson setup of the server: nulls are kept so the state JSON has the same shape as the JS engine's. */
public final class Json {

    public static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private Json() {}

    public static String write(Object o) { return GSON.toJson(o); }

    public static <T> T read(String s, Class<T> type) { return GSON.fromJson(s, type); }

    public static JsonElement parse(String s) { return JsonParser.parseString(s); }

    /**
     * Reads a browser data file of the form {@code window.NAME = {...};} (data/cards.js,
     * data/rules.js, data/map.js) and returns the JSON value.
     */
    public static JsonElement readJsData(Path file) {
        try {
            String src = Files.readString(file, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("window\\.[A-Za-z_]+\\s*=\\s*").matcher(src);
            if (!m.find()) throw new IllegalStateException("Not a data file: " + file);
            int start = src.indexOf('{', m.end());
            int end = src.lastIndexOf('}');
            if (start < 0 || end < start) throw new IllegalStateException("Not a data file: " + file);
            return JsonParser.parseString(src.substring(start, end + 1));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }
}

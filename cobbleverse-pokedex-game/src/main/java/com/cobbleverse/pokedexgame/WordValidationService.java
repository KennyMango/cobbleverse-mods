package com.cobbleverse.pokedexgame;

import java.io.IOException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asynchronously validates 5-letter English guesses against Datamuse.
 * Results are persisted locally so a word normally only needs one API request ever.
 */
public final class WordValidationService {
    public enum Result { VALID, INVALID, UNAVAILABLE }

    private final Path cachePath;
    private final HttpClient client;
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();
    private volatile GameConfig config;

    public WordValidationService(Path directory, GameConfig config) {
        this.cachePath = directory.resolve("word-validation-cache.txt");
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, config.wordValidationTimeoutSeconds)))
                .build();
        loadCache();
    }

    public void updateConfig(GameConfig config) {
        this.config = config;
    }

    public CompletableFuture<Result> validate(String rawWord) {
        String word = WordleDictionary.normalize(rawWord);
        if (!word.matches("[A-Z]{5}")) return CompletableFuture.completedFuture(Result.INVALID);

        Boolean cached = cache.get(word);
        if (cached != null) return CompletableFuture.completedFuture(cached ? Result.VALID : Result.INVALID);

        GameConfig cfg = config;
        if (!cfg.wordValidationApiEnabled) return CompletableFuture.completedFuture(Result.UNAVAILABLE);

        return inFlight.computeIfAbsent(word, key -> request(key, cfg)
                .whenComplete((result, error) -> inFlight.remove(key)));
    }

    private CompletableFuture<Result> request(String word, GameConfig cfg) {
        String base = cfg.wordValidationApiBaseUrl;
        if (base == null || base.isBlank()) return CompletableFuture.completedFuture(Result.UNAVAILABLE);

        URI uri;
        try {
            String encoded = URLEncoder.encode(word.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8);
            // Default Datamuse base URL ends in ?sp=. Add the exact guess and ask for
            // enough candidates to reliably find an exact spelling match.
            String full = base + encoded;
            if (base.contains("api.datamuse.com/words")) full += "&max=100";
            uri = URI.create(full);
        } catch (Exception e) {
            PokedexGameMod.LOGGER.warn("Invalid word validation API URL: {}", base, e);
            return CompletableFuture.completedFuture(Result.UNAVAILABLE);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(1, cfg.wordValidationTimeoutSeconds)))
                .header("Accept", "application/json")
                .header("User-Agent", "Cobbleverse-PokedexGame/0.5.7")
                .GET()
                .build();

        PokedexGameMod.LOGGER.info("[Pokedle API] Checking '{}' -> {}", word, uri);

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error != null) {
                        PokedexGameMod.LOGGER.error("[Pokedle API] Request FAILED for '{}' -> {}", word, uri, error);
                        return Result.UNAVAILABLE;
                    }

                    int code = response.statusCode();
                    String body = response.body() == null ? "" : response.body();
                    PokedexGameMod.LOGGER.info("[Pokedle API] '{}' HTTP {} ({} bytes)", word, code, body.length());

                    if (code >= 200 && code < 300) {
                        try {
                            JsonElement parsed = JsonParser.parseString(body);
                            if (!parsed.isJsonArray()) {
                                PokedexGameMod.LOGGER.warn("[Pokedle API] Datamuse returned non-array JSON for '{}'.", word);
                                return Result.UNAVAILABLE;
                            }

                            JsonArray results = parsed.getAsJsonArray();
                            boolean exactMatch = false;
                            for (JsonElement element : results) {
                                if (!element.isJsonObject()) continue;
                                JsonObject object = element.getAsJsonObject();
                                JsonElement candidate = object.get("word");
                                if (candidate != null && candidate.isJsonPrimitive()
                                        && word.equalsIgnoreCase(candidate.getAsString())) {
                                    exactMatch = true;
                                    break;
                                }
                            }

                            PokedexGameMod.LOGGER.info("[Pokedle API] '{}' = {} (Datamuse exact match)",
                                    word, exactMatch ? "VALID" : "INVALID");
                            remember(word, exactMatch);
                            return exactMatch ? Result.VALID : Result.INVALID;
                        } catch (Exception parseError) {
                            PokedexGameMod.LOGGER.error("[Pokedle API] Could not parse Datamuse response for '{}'", word, parseError);
                            return Result.UNAVAILABLE;
                        }
                    }

                    // API 5xx responses are service failures, not
                    // evidence that the word is invalid. Do not poison the persistent cache.
                    if (code >= 500 && code <= 599) {
                        PokedexGameMod.LOGGER.warn("[Pokedle API] Service unavailable for '{}': HTTP {}. Falling back to local dictionary; attempt will not be consumed if unknown locally.", word, code);
                        return Result.UNAVAILABLE;
                    }

                    String preview = body.length() > 500 ? body.substring(0, 500) + "..." : body;
                    PokedexGameMod.LOGGER.warn("[Pokedle API] Unexpected HTTP {} for '{}'. Body: {}", code, word, preview);
                    return Result.UNAVAILABLE;
                });
    }

    private synchronized void remember(String word, boolean valid) {
        cache.put(word, valid);
        try {
            Files.createDirectories(cachePath.getParent());
            StringBuilder out = new StringBuilder("# Pokedle API word validation cache. Format: WORD=true|false\n");
            cache.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> out.append(e.getKey()).append('=').append(e.getValue()).append('\n'));
            Files.writeString(cachePath, out.toString());
        } catch (IOException e) {
            PokedexGameMod.LOGGER.warn("Could not save word validation cache {}", cachePath, e);
        }
    }

    private void loadCache() {
        if (!Files.exists(cachePath)) return;
        try {
            for (String raw : Files.readAllLines(cachePath)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int split = line.indexOf('=');
                if (split <= 0) continue;
                String word = WordleDictionary.normalize(line.substring(0, split));
                if (!word.matches("[A-Z]{5}")) continue;
                String value = line.substring(split + 1).trim();
                if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                    cache.put(word, Boolean.parseBoolean(value));
                }
            }
            PokedexGameMod.LOGGER.info("Loaded {} cached API word validations", cache.size());
        } catch (IOException e) {
            PokedexGameMod.LOGGER.warn("Could not load word validation cache {}", cachePath, e);
        }
    }
}

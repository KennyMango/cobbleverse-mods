package com.cobbleverse.pokedexgame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Editable Wordle dictionaries. Files are created under
 * config/cobbleverse-pokedex-game/ on first launch and can be expanded without rebuilding the mod.
 */
public final class WordleDictionary {
    private static final List<String> DEFAULT_ANSWERS = List.of(
            // 5-letter Pokémon names from the early National Pokédex
            "EKANS","ARBOK","ZUBAT","GLOOM","PARAS","GOLEM","DODUO","HYPNO","DITTO","EEVEE",
            "PICHU","AIPOM","YANMA","UNOWN","MAGBY","ENTEI","LUGIA","LOTAD","RALTS","MINUN",
            "NUMEL","ABSOL","BAGON","SHINX","LUXIO","BUDEW","BURMY","GIBLE","RIOLU","ROTOM","AZELF",
            // Recognizable Pokémon terms, types, moves and battle vocabulary
            "BERRY","FAIRY","GRASS","WATER","STEEL","GHOST","SWIFT","GROWL","ROOST","TOXIC",
            "SLASH","FLAME","SOLAR","DRAIN","DANCE","PULSE","FORCE","GUARD","POWER","STONE",
            "SCALE","CLAWS","FANGS","WINGS","SHELL","HORNS","SHINY","TRAIN","LEVEL","BADGE",
            "RIVAL","NURSE","ELITE","CHAMP","HATCH","CATCH","THROW","BOOST","SPEED","TYPES",
            "STATS","MOVES","PARTY","TRADE","BREED","SPAWN","ROUTE"
    );

    // Extra legal guesses that do not have to be chosen as answers.
    private static final List<String> DEFAULT_EXTRA_GUESSES = List.of(
            "ARENA","BLOCK","CHARM","DREAM","FIELD","FLASH","FOCUS","HEART","LIGHT","LUCKY",
            "MAGIC","NIGHT","QUICK","ROUND","SHARD","SMOKE","SPARK","STORM","SWORD","TOWER",
            "TRICK","ULTRA","WORLD"
    );

    private final Path answersPath;
    private final Path guessesPath;
    private List<String> answers = List.of();
    private Set<String> validGuesses = Set.of();

    public WordleDictionary(Path directory) {
        this.answersPath = directory.resolve("wordle-answers.txt");
        this.guessesPath = directory.resolve("wordle-valid-guesses.txt");
        reload();
    }

    public void reload() {
        try {
            Files.createDirectories(answersPath.getParent());
            createIfMissing(answersPath, DEFAULT_ANSWERS,
                    "# One possible 5-letter Wordle answer per line.\n# Letters A-Z only. Lines beginning with # are comments.\n");
            createIfMissing(guessesPath, DEFAULT_EXTRA_GUESSES,
                    "# Extra accepted 5-letter guesses. Answers are always accepted too.\n# Letters A-Z only. Lines beginning with # are comments.\n");

            LinkedHashSet<String> answerSet = readWords(answersPath);
            LinkedHashSet<String> guessSet = readWords(guessesPath);
            guessSet.addAll(answerSet);

            answers = List.copyOf(answerSet);
            validGuesses = Set.copyOf(guessSet);
            PokedexGameMod.LOGGER.info("Loaded {} Wordle answers and {} total valid guesses", answers.size(), validGuesses.size());
        } catch (Exception e) {
            PokedexGameMod.LOGGER.error("Could not load Wordle dictionaries", e);
            LinkedHashSet<String> fallback = new LinkedHashSet<>(DEFAULT_ANSWERS);
            answers = List.copyOf(fallback);
            fallback.addAll(DEFAULT_EXTRA_GUESSES);
            validGuesses = Set.copyOf(fallback);
        }
    }

    public List<String> answers() { return answers; }
    public boolean isValidGuess(String word) { return validGuesses.contains(normalize(word)); }
    public boolean isAnswer(String word) { return answers.contains(normalize(word)); }

    public static String normalize(String word) {
        return word == null ? "" : word.trim().toUpperCase(Locale.ROOT);
    }

    private static void createIfMissing(Path path, List<String> defaults, String header) throws IOException {
        if (Files.exists(path)) return;
        StringBuilder out = new StringBuilder(header);
        for (String word : defaults) out.append(word).append('\n');
        Files.writeString(path, out.toString());
    }

    private static LinkedHashSet<String> readWords(Path path) throws IOException {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        int lineNo = 0;
        for (String raw : Files.readAllLines(path)) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String word = normalize(line);
            if (!word.matches("[A-Z]{5}")) {
                PokedexGameMod.LOGGER.warn("Skipping invalid Wordle entry {}:{} -> '{}' (must be exactly 5 letters A-Z)", path.getFileName(), lineNo, line);
                continue;
            }
            result.add(word);
        }
        return result;
    }
}

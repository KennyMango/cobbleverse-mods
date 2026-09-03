package com.cobbleverse.pokedexgame;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Uses reflection deliberately so this tiny server-side mod is resilient to
 * small binary/API changes between Cobblemon 1.7.x builds.
 */
public final class CobblemonSpeciesAccess {
    private static final String REGISTRY_CLASS = "com.cobblemon.mod.common.api.pokemon.PokemonSpecies";

    public record SpeciesInfo(
            Object raw,
            String name,
            int dex,
            String primaryType,
            String secondaryType,
            Double height,
            Double weight,
            Double maleRatio,
            List<String> eggGroups,
            List<String> abilities,
            Integer baseStatTotal
    ) {}

    @SuppressWarnings("unchecked")
    public static List<SpeciesInfo> implemented(int minDex, int maxDex) {
        try {
            Class<?> registry = Class.forName(REGISTRY_CLASS);
            Method getImplemented = registry.getMethod("getImplemented");
            Object result = getImplemented.invoke(null);
            if (!(result instanceof Collection<?> collection)) return List.of();

            List<SpeciesInfo> out = new ArrayList<>();
            for (Object raw : collection) {
                SpeciesInfo info = inspect(raw);
                if (info != null && info.dex >= minDex && info.dex <= maxDex) out.add(info);
            }
            return out;
        } catch (Throwable t) {
            PokedexGameMod.LOGGER.error("Unable to read Cobblemon species registry", t);
            return List.of();
        }
    }

    public static SpeciesInfo byGuess(String guess) {
        String normalized = normalize(guess);
        try {
            Class<?> registry = Class.forName(REGISTRY_CLASS);
            Method getByName = registry.getMethod("getByName", String.class);
            Object raw = getByName.invoke(null, normalized);
            return raw == null ? null : inspect(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean matches(SpeciesInfo species, String guess) {
        if (species == null) return false;
        String a = normalize(species.name);
        String b = normalize(guess);
        if (a.equals(b)) return true;

        Object identifier = property(species.raw, "getResourceIdentifier", "resourceIdentifier");
        if (identifier != null) {
            String text = identifier.toString();
            int colon = text.indexOf(':');
            if (colon >= 0) text = text.substring(colon + 1);
            return normalize(text).equals(b);
        }
        return false;
    }

    private static SpeciesInfo inspect(Object raw) {
        if (raw == null) return null;
        String name = stringProperty(raw, "getName", "name");
        Integer dex = intProperty(raw, "getNationalPokedexNumber", "nationalPokedexNumber");
        if (name == null || dex == null) return null;

        String primary = typeName(property(raw, "getPrimaryType", "primaryType"));
        String secondary = typeName(property(raw, "getSecondaryType", "secondaryType"));
        Double height = doubleProperty(raw, "getHeight", "height");
        Double weight = doubleProperty(raw, "getWeight", "weight");
        Double maleRatio = doubleProperty(raw, "getMaleRatio", "maleRatio");
        List<String> eggGroups = stringCollection(property(raw, "getEggGroups", "eggGroups"));
        List<String> abilities = abilityNames(property(raw, "getAbilities", "abilities"));
        Integer bst = baseStatTotal(property(raw, "getBaseStats", "baseStats"));
        return new SpeciesInfo(raw, name, dex, primary, secondary, height, weight, maleRatio, eggGroups, abilities, bst);
    }

    private static String normalize(String input) {
        if (input == null) return "";
        return input.toLowerCase(Locale.ROOT).replace("é", "e").replaceAll("[^a-z0-9]", "");
    }

    private static Object property(Object obj, String... names) {
        if (obj == null) return null;
        for (String name : names) {
            try {
                Method m = obj.getClass().getMethod(name);
                return m.invoke(obj);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String stringProperty(Object obj, String... names) {
        Object v = property(obj, names);
        return v == null ? null : String.valueOf(v);
    }

    private static Integer intProperty(Object obj, String... names) {
        Object v = property(obj, names);
        return v instanceof Number n ? n.intValue() : null;
    }

    private static Double doubleProperty(Object obj, String... names) {
        Object v = property(obj, names);
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static String typeName(Object type) {
        if (type == null) return null;
        Object name = property(type, "getName", "name");
        return name == null ? String.valueOf(type) : String.valueOf(name);
    }

    private static List<String> stringCollection(Object value) {
        if (!(value instanceof Collection<?> c)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : c) {
            Object name = property(item, "getName", "name");
            out.add(name == null ? String.valueOf(item) : String.valueOf(name));
        }
        return out;
    }


    private static List<String> abilityNames(Object value) {
        if (!(value instanceof Collection<?> c)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : c) {
            Object ability = property(item, "getAbility", "ability");
            Object target = ability == null ? item : ability;
            Object name = property(target, "getName", "name");
            String text = name == null ? String.valueOf(target) : String.valueOf(name);
            if (text != null && !text.isBlank() && !text.contains("@")) out.add(text);
        }
        return out;
    }
    private static Integer baseStatTotal(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        int total = 0;
        boolean any = false;
        for (Object stat : map.values()) {
            if (stat instanceof Number n) {
                total += n.intValue();
                any = true;
            }
        }
        return any ? total : null;
    }
}

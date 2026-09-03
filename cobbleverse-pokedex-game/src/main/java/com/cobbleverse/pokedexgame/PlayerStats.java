package com.cobbleverse.pokedexgame;

public final class PlayerStats {
    public String lastKnownName = "Unknown";
    public int points = 0;
    // Total successful daily puzzles (Pokédex + Wordle).
    public int correctGuesses = 0;
    public int totalHintsAtSolve = 0;
    public int pokedexWins = 0;
    public int wordleWins = 0;

    public double averageHints() {
        return pokedexWins == 0 ? 0.0 : (double) totalHintsAtSolve / pokedexWins;
    }
}

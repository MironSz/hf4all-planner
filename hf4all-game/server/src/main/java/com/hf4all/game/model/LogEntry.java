package com.hf4all.game.model;

/** One line of the game log. kind: info | system | good | bad | event | dice. */
public final class LogEntry {
    public int year;
    public int player;
    public String text;
    public String kind;

    public LogEntry() {}

    public LogEntry(int year, int player, String text, String kind) {
        this.year = year;
        this.player = player;
        this.text = text;
        this.kind = kind;
    }
}

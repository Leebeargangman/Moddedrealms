package com.colonysmp.model;

/** Something worth remembering in a colony's history: what happened, when, and in which area of life. */
public record ColonyEvent(long id, long time, long day, Type type, String message) {

    public enum Type {
        COLONY("Colony", "<aqua>"), POPULATION("Population", "<green>"), ECONOMY("Economy", "<gold>"), PRODUCTION("Production", "<yellow>"),
        CONSTRUCTION("Construction", "<white>"), HEALTH("Health", "<light_purple>"), CRIME("Order", "<red>"),
        MILITARY("Military", "<dark_red>"), DIPLOMACY("Diplomacy", "<blue>"), SIMULATION("Background", "<gray>");

        public final String display, color;

        Type(String display, String color) {
            this.display = display;
            this.color = color;
        }

        public static Type parse(String s) {
            try {
                return valueOf(s);
            } catch (RuntimeException e) {
                return COLONY;
            }
        }
    }
}

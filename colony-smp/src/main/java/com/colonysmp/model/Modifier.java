package com.colonysmp.model;

/**
 * A temporary effect on the whole colony: a victory, a defeat, a riot, a plague. It raises or lowers every
 * citizen's mood until it runs out.
 */
public record Modifier(String key, String label, double mood, long untilDay) {
}

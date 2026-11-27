package com.colonysmp.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Goods set aside for one task (a recipe, a delivery, a sale). While reserved they are still in stock but no
 * other task can use them. A reservation is consumed (the goods leave), partly consumed, or released.
 */
public final class Reservation {

    public final String id;
    /** Who holds it, for the admin view: "prod:12" (building 12's production), "deliver:...", "sale:...". */
    public final String owner;
    public final long created;
    final Map<String, Long> remaining = new LinkedHashMap<>();

    Reservation(String id, String owner, long created) {
        this.id = id;
        this.owner = owner;
        this.created = created;
    }

    public Map<String, Long> goods() {
        return Collections.unmodifiableMap(remaining);
    }

    public long get(String key) {
        return remaining.getOrDefault(key, 0L);
    }

    public boolean empty() {
        for (long v : remaining.values()) if (v > 0) return false;
        return true;
    }

    @Override
    public String toString() {
        return id + "(" + owner + ") " + remaining;
    }
}

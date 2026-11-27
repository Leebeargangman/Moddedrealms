package com.colonysmp.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One batch of work at a workplace: inputs in, outputs out, after some hours of work.
 *
 * <p>Input keys are goods ("wheat"), groups ("#cooked_meat": any one of several goods), or {@link #FUEL} (fuel
 * units: a lump of coal is 8). Outputs are goods; tools, weapons and armour among them are made as items and go
 * to the colony's equipment store.
 */
public final class Recipe {

    public static final String FUEL = "fuel";

    public final String id;
    public final String name;
    public final String station;
    public final Map<String, Integer> inputs;
    public final Map<String, Integer> outputs;
    /** Work hours for one batch at normal speed. */
    public final double hours;
    public final Skill skill;
    /** Building level needed. */
    public final int minLevel;
    /** Made only when something asks for it (tools and arms), never just to fill the shelves. */
    public final boolean onDemand;

    public Recipe(String id, String name, String station, Map<String, Integer> inputs, Map<String, Integer> outputs,
                  double hours, Skill skill, int minLevel, boolean onDemand) {
        this.id = id;
        this.name = name;
        this.station = station;
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        this.hours = hours;
        this.skill = skill;
        this.minLevel = minLevel;
        this.onDemand = onDemand;
    }

    /** The output this recipe is "for" (stock targets look at it). */
    public String primary() {
        return outputs.keySet().iterator().next();
    }

    public int fuel() {
        return inputs.getOrDefault(FUEL, 0);
    }

    @Override
    public String toString() {
        return id;
    }
}

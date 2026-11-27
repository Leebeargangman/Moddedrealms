package com.colonysmp.model;

import com.colonysmp.util.BlockPos;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A construction project: a standard plan or a copied structure, at a spot, facing a way. It is planned until a
 * builder places the first block, under construction after that, and gone once it's complete and registered.
 */
public final class BuildOrder {

    public enum State {
        PLANNED("Planned"), UNDER_CONSTRUCTION("Under construction"), PAUSED("Paused");

        public final String display;

        State(String display) {
            this.display = display;
        }
    }

    public final int id;
    /** A standard plan, or null for a copied structure ({@link #custom}). */
    public final BuildingType type;
    /** Name of the colony's copied blueprint being built, or null. */
    public final String custom;
    public final BlockPos origin;
    public final BlockFace facing;
    public int step;
    public State state = State.PLANNED;
    public final long created;
    /** What the builders are waiting for (display only). */
    public transient String waitingFor;
    public transient long lastWarn;

    public BuildOrder(int id, BuildingType type, String custom, BlockPos origin, BlockFace facing, long created) {
        this.id = id;
        this.type = type;
        this.custom = custom;
        this.origin = origin;
        this.facing = facing;
        this.created = created;
    }

    public String label() {
        return custom != null ? custom : type.display;
    }

    public void save(ConfigurationSection s) {
        s.set("type", type == null ? null : type.name());
        s.set("custom", custom);
        s.set("origin", origin.toString());
        s.set("facing", facing.name());
        s.set("step", step);
        s.set("state", state.name());
        s.set("created", created);
    }

    public static BuildOrder load(int id, ConfigurationSection s) {
        BuildingType t = BuildingType.parse(s.getString("type"));
        String custom = s.getString("custom");
        BlockPos o = BlockPos.parse(s.getString("origin"));
        if ((t == null && custom == null) || o == null) return null;
        BlockFace f;
        try {
            f = BlockFace.valueOf(s.getString("facing", "NORTH"));
        } catch (IllegalArgumentException e) {
            f = BlockFace.NORTH;
        }
        BuildOrder j = new BuildOrder(id, custom != null ? null : t, custom, o, f, s.getLong("created"));
        j.step = s.getInt("step");
        try {
            j.state = State.valueOf(s.getString("state", "PLANNED"));
        } catch (IllegalArgumentException e) {
            j.state = State.PLANNED;
        }
        return j;
    }
}

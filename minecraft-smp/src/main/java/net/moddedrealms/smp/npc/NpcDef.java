package net.moddedrealms.smp.npc;

import java.util.EnumMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.EquipmentSlot;

/** One NPC from npcs.yml. */
public final class NpcDef {
    public String id;
    public String name;
    public String title;
    public String world;
    public double x, y, z;
    public float yaw;
    /** Minecraft account name whose skin to wear (resolved by the client/Mojang), or null for the default look. */
    public String skin;
    /** A skin from the server resource pack (textures/entity/npc/<texture>.png); wins over skin when the pack is on. */
    public String texture;
    /** What a click does: dialogue (default: dialogue:<id>), quests, command:<cmd>, console:<cmd>. */
    public String action;
    public boolean look = true;
    /** Strolls around its spot within this radius (0 = stands still). */
    public double wander;
    /** Set by the plaza builder, so "undo" can remove it again. */
    public String group;
    public final Map<EquipmentSlot, Material> equipment = new EnumMap<>(EquipmentSlot.class);
    public Color leatherColor;

    public Location home() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, 0);
    }

    public void setHome(Location l) {
        world = l.getWorld().getName();
        x = l.getX();
        y = l.getY();
        z = l.getZ();
        yaw = l.getYaw();
    }

    static NpcDef load(String id, ConfigurationSection s) {
        NpcDef d = new NpcDef();
        d.id = id;
        d.name = s.getString("name", id);
        d.title = s.getString("title");
        d.world = s.getString("world", "world");
        d.x = s.getDouble("x");
        d.y = s.getDouble("y");
        d.z = s.getDouble("z");
        d.yaw = (float) s.getDouble("yaw");
        d.skin = s.getString("skin");
        d.texture = s.getString("texture");
        d.action = s.getString("action");
        d.look = s.getBoolean("look", true);
        d.wander = s.getDouble("wander", 0);
        d.group = s.getString("group");
        ConfigurationSection eq = s.getConfigurationSection("equipment");
        if (eq != null) {
            for (String k : eq.getKeys(false)) {
                try {
                    EquipmentSlot slot = EquipmentSlot.valueOf(k.toUpperCase());
                    Material m = Material.matchMaterial(eq.getString(k, ""));
                    if (m != null) d.equipment.put(slot, m);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        String col = s.getString("leather-color");
        if (col != null && col.startsWith("#") && col.length() == 7) d.leatherColor = Color.fromRGB(Integer.parseInt(col.substring(1), 16));
        return d;
    }

    void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("title", title);
        s.set("world", world);
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
        s.set("yaw", (double) yaw);
        s.set("skin", skin);
        s.set("texture", texture);
        s.set("action", action);
        s.set("look", look);
        s.set("wander", wander == 0 ? null : wander);
        s.set("group", group);
        s.set("equipment", null);
        for (var e : equipment.entrySet()) s.set("equipment." + e.getKey().name().toLowerCase(), e.getValue().name());
        s.set("leather-color", leatherColor == null ? null : String.format("#%06X", leatherColor.asRGB()));
    }
}

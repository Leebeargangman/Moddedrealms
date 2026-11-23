package net.moddedrealms.smp;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.profile.PlayerTextures;

/**
 * The Moddedrealms resource pack: NPC skins (textures/entity/npc/*.png, applied through the mannequin's profile), the
 * train models and item art. Everything here is switched off with resource-pack.models: false, for a server that
 * doesn't send the pack; NPCs then keep their old clothes.
 */
public final class Pack {
    public static final String NS = "moddedrealms";
    private static boolean on;
    private static final Random R = new Random();
    /** How many variants each generated family has (gen_skins.py VARIANTS). */
    private static final Map<String, Integer> VARIANTS = Map.of("merchant", 6, "worker", 6, "gang_vipers", 4, "gang_jackals", 4,
            "resident_m", 12, "resident_f", 12, "child", 8);

    /** The skin of each fixed NPC, by id. Anything not listed keeps its configured look. */
    private static final Map<String, String> NPC_SKINS = Map.ofEntries(
            Map.entry("questmaster", "questmaster"), Map.entry("cratekeeper", "merchant_0"), Map.entry("enchanter", "enchanter"),
            Map.entry("herald", "crier"), Map.entry("envoy", "lawyer"), Map.entry("captain", "captain"), Map.entry("elder", "elder"),
            Map.entry("townsfolk_1", "resident_m_0"), Map.entry("townsfolk_2", "resident_f_0"), Map.entry("townsfolk_3", "resident_m_1"),
            Map.entry("warden", "warden"), Map.entry("jailguard", "officer"), Map.entry("banker", "banker"),
            Map.entry("dealer_vito", "croupier"), Map.entry("dealer_lena", "croupier_f"), Map.entry("croupier", "croupier"),
            Map.entry("dice_lou", "croupier"), Map.entry("dice_mae", "croupier_f"), Map.entry("bartender", "bartender"),
            Map.entry("planner", "fence"), Map.entry("bouncer", "bouncer"), Map.entry("landagent", "lawyer_f"),
            Map.entry("mb_crier", "crier"), Map.entry("mb_shop1", "merchant_1"), Map.entry("mb_shop2", "merchant_2"),
            Map.entry("mb_shop3", "chef"), Map.entry("mb_millowner", "worker_0"), Map.entry("mb_lumber", "worker_1"),
            Map.entry("mb_judge", "judge"), Map.entry("mb_editor", "editor"), Map.entry("mb_banker", "banker"),
            Map.entry("mb_sheriff", "sheriff"), Map.entry("mb_hotel", "bellhop"), Map.entry("mb_mayor", "mayor"),
            Map.entry("rail_clerk_south", "conductor"), Map.entry("rail_clerk_north", "conductor"));

    private Pack() {
    }

    public static void load(FileConfiguration c) {
        on = c.getBoolean("resource-pack.models", true);
    }

    public static boolean on() {
        return on;
    }

    /** A random one of a family ("resident_f" -> "resident_f_7"), or the name itself for a single skin. */
    public static String pick(String family) {
        Integer n = VARIANTS.get(family);
        return n == null ? family : family + "_" + R.nextInt(n);
    }

    /** The same variant every time for the same id (so a resident keeps their face). */
    public static String pick(String family, UUID id) {
        Integer n = VARIANTS.get(family);
        return n == null ? family : family + "_" + Math.floorMod(id.hashCode(), n);
    }

    public static String npcSkin(String npcId) {
        return NPC_SKINS.get(npcId);
    }

    /** Dress a mannequin in a pack skin; armour is taken off (the skin has the uniform). Returns false if the pack is off. */
    public static boolean skin(Mannequin m, String skin) {
        if (!on || skin == null) return false;
        m.setProfile(ResolvableProfile.resolvableProfile()
                .skinPatch(p -> p.body(Key.key(NS, "entity/npc/" + skin)).model(PlayerTextures.SkinModel.CLASSIC)).build());
        var eq = m.getEquipment();
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) eq.setItem(s, null);
        return true;
    }

    /** Give an item its pack art (models/item/<model>.json); a no-op with the pack off. */
    public static ItemStack model(ItemStack it, String model) {
        if (!on) return it;
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey(NS, model));
        it.setItemMeta(m);
        return it;
    }
}

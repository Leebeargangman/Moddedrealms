package com.colonysmp.util;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;

/** The plugin's own items, their recipes, and a guard that keeps them (and colony goods) out of vanilla recipes. */
public final class Items implements Listener {

    public static final String WAND = "wand", CORE = "core", BOOK = "book", ROPE = "rope", BATON = "baton", WAR_BANNER = "war_banner",
            MARKER = "marker", CHRISTMAS_SAPLING = "christmas_sapling", PRESENT = "present";

    private final Plugin plugin;

    public Items(Plugin plugin) {
        this.plugin = plugin;
    }

    // ───────────── identification ─────────────

    public static String id(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(Keys.ITEM, PersistentDataType.STRING);
    }

    public static boolean is(ItemStack it, String id) {
        return id.equals(id(it));
    }

    public static String colonyOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(Keys.COLONY, PersistentDataType.STRING);
    }

    // ───────────── factories ─────────────

    private ItemStack make(Material m, String id, String name, boolean glint, int maxStack, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item(name));
        meta.lore(Text.lore(lore));
        meta.getPersistentDataContainer().set(Keys.ITEM, PersistentDataType.STRING, id);
        if (glint) meta.setEnchantmentGlintOverride(true);
        if (maxStack > 0) meta.setMaxStackSize(maxStack);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(meta);
        return it;
    }

    public ItemStack wand() {
        return make(Material.STICK, WAND, "<gold><bold>Colony Wand", true, 1,
                "<gray>Copies buildings as blueprints.",
                "",
                "<green>Left-Click</green> <gray>a block: <white>corner 1",
                "<white>Right-Click</white> <gray>a block: <white>corner 2",
                "<yellow>Shift + Right-Click</yellow><gray>: <white>copy as a blueprint");
    }

    public ItemStack core(String colonyId, String colonyName) {
        ItemStack it = make(Material.LODESTONE, CORE, "<aqua><bold>Town Hall Core", true, 1,
                "<gray>The heart of <white>" + Text.esc(colonyName) + "</white>.",
                "",
                "<gray>Place it where the colony should stand:",
                "<gray>the land around it is claimed, a storage",
                "<gray>chest appears beside it and your first",
                "<gray>settlers arrive.");
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(Keys.COLONY, PersistentDataType.STRING, colonyId);
        it.setItemMeta(meta);
        return it;
    }

    public ItemStack book() {
        return make(Material.BOOK, BOOK, "<yellow><bold>Blueprint Book", true, 1,
                "<gray>Plans and registers colony buildings.",
                "",
                "<white>Hold</white> <gray>to preview the selected plan",
                "<white>Right-Click</white><gray>: next plan (Shift: previous)",
                "<yellow>Shift + Right-Click</yellow> <gray>a block: <white>register",
                "<gray>  the building you're standing in or at",
                "<gold>Shift + Left-Click</gold><gray>: <white>order construction");
    }

    public ItemStack marker() {
        ItemStack it = make(Material.LIGHT_BLUE_BANNER, MARKER, "<aqua><bold>Building Marker", true, 16,
                "<gray>Place it inside a building you've built:",
                "<gray>it works out what the building is (a house,",
                "<gray>a bakery, a farm...) and registers it.",
                "",
                "<white>Place</white> <gray>inside a room, on farmland, in a fenced",
                "<gray>  yard or on top of a tower",
                "<white>Right-Click</white> <gray>it: <white>inspect again",
                "<yellow>Shift + Right-Click</yellow> <gray>it: <white>choose another type",
                "<white>Break</white> <gray>it once registered: the building stays");
        if (it.getItemMeta() instanceof BannerMeta bm) {
            bm.setPatterns(List.of(new Pattern(DyeColor.WHITE, PatternType.HALF_HORIZONTAL_BOTTOM), new Pattern(DyeColor.BLUE, PatternType.TRIANGLE_TOP),
                    new Pattern(DyeColor.BLUE, PatternType.BORDER)));
            it.setItemMeta(bm);
        }
        return it;
    }

    public ItemStack rope() {
        return make(Material.LEAD, ROPE, "<#c9a26b>Rope", false, 16,
                "<gray>Right-click a <red>downed</red> enemy to bind them.",
                "<gray>Lead them into a <white>Prison</white>, or",
                "<gray>Shift + Right-click to hand them to your Guards.");
    }

    public ItemStack baton() {
        return make(Material.BLAZE_ROD, BATON, "<red><bold>Command Baton", true, 1,
                "<gray>Commands your colony's squads.",
                "",
                "<white>Right-Click</white> <gray>a block: <white>rally the selected squad there",
                "<yellow>Shift + Right-Click</yellow><gray>: <white>next order",
                "<white>Left-Click</white> <gray>an enemy: <white>focus target",
                "<gold>Shift + Left-Click</gold><gray>: <white>select the next squad");
    }

    public ItemStack warBanner() {
        ItemStack it = make(Material.RED_BANNER, WAR_BANNER, "<dark_red><bold>War Banner", true, 1,
                "<gray>A declaration of war against another colony.",
                "",
                "<white>Right-Click</white> <gray>(or <white>/colony declarewar</white>) to open war options.",
                "<white>Place</white> <gray>it outside the enemy's land during the",
                "<gray>warm-up to raise your <red>War Camp</red>.");
        if (it.getItemMeta() instanceof BannerMeta bm) {
            bm.setPatterns(List.of(new Pattern(DyeColor.BLACK, PatternType.CROSS), new Pattern(DyeColor.RED, PatternType.BORDER)));
            bm.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            it.setItemMeta(bm);
        }
        return it;
    }

    public ItemStack christmasSapling() {
        return make(Material.SPRUCE_SAPLING, CHRISTMAS_SAPLING, "<green><bold>Christmas Tree Sapling", true, 16,
                "<gray>Plant it on open ground in your colony",
                "<gray>during the Christmas season: it grows at once",
                "<gray>into the colony's <green>Christmas tree</green>, with",
                "<gray>lanterns in its branches and a star on top.",
                "",
                "<gray>A colony with a tree is merrier, and",
                "<red>Father Christmas</red> <gray>only calls where there is one.");
    }

    public ItemStack present() {
        return make(Material.RED_BUNDLE, PRESENT, "<red><bold>Christmas Present", true, 16,
                "<gray>From Father Christmas.",
                "",
                "<white>Right-Click</white><gray>: <white>unwrap it");
    }

    public ItemStack byId(String id) {
        return switch (id) {
            case CHRISTMAS_SAPLING -> christmasSapling();
            case PRESENT -> present();
            case WAND -> wand();
            case BOOK -> book();
            case MARKER -> marker();
            case ROPE -> rope();
            case BATON -> baton();
            case WAR_BANNER -> warBanner();
            default -> null;
        };
    }

    /** A menu icon. Each gets a unique tag so icons never stack or merge with real items. */
    public static ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m.isItem() ? m : Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item(name));
        if (lore != null && !lore.isEmpty()) meta.lore(Text.lore(lore));
        meta.addItemFlags(ItemFlag.values());
        meta.getPersistentDataContainer().set(Keys.UNIQUE, PersistentDataType.STRING, UUID.randomUUID().toString());
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack icon(Material m, String name, String... lore) {
        return icon(m, name, List.of(lore));
    }

    public static ItemStack glow(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    // ───────────── recipes ─────────────

    public void registerRecipes() {
        ShapedRecipe wand = new ShapedRecipe(Keys.RECIPE_WAND, wand());
        wand.shape("SG");
        wand.setIngredient('S', Material.STICK);
        wand.setIngredient('G', Material.GOLD_NUGGET);
        add(wand);

        ItemStack ropes = rope();
        ropes.setAmount(2);
        ShapedRecipe rope = new ShapedRecipe(Keys.RECIPE_ROPE, ropes);
        rope.shape("S", "S", "S");
        rope.setIngredient('S', Material.STRING);
        add(rope);

        ShapelessRecipe banner = new ShapelessRecipe(Keys.RECIPE_BANNER, warBanner());
        banner.addIngredient(Material.RED_BANNER);
        banner.addIngredient(Material.IRON_SWORD);
        banner.addIngredient(Material.GOLD_INGOT);
        add(banner);

        ShapelessRecipe book = new ShapelessRecipe(Keys.RECIPE_BOOK, book());
        book.addIngredient(Material.BOOK);
        book.addIngredient(Material.GOLD_NUGGET);
        book.addIngredient(Material.PAPER);
        add(book);

        ItemStack markers = marker();
        markers.setAmount(2);
        ShapelessRecipe marker = new ShapelessRecipe(Keys.RECIPE_MARKER, markers);
        marker.addIngredient(new RecipeChoice.MaterialChoice(Tag.ITEMS_BANNERS));
        marker.addIngredient(Material.PAPER);
        marker.addIngredient(Material.GOLD_NUGGET);
        add(marker);

        ShapelessRecipe tree = new ShapelessRecipe(Keys.RECIPE_CHRISTMAS, christmasSapling());
        tree.addIngredient(Material.SPRUCE_SAPLING);
        tree.addIngredient(Material.GOLD_NUGGET);
        tree.addIngredient(Material.RED_DYE);
        add(tree);

        ShapelessRecipe baton = new ShapelessRecipe(Keys.RECIPE_BATON, baton());
        baton.addIngredient(Material.STICK);
        baton.addIngredient(Material.GOLD_INGOT);
        baton.addIngredient(Material.RED_DYE);
        add(baton);
    }

    private void add(Recipe r) {
        try {
            if (r instanceof ShapedRecipe s) Bukkit.removeRecipe(s.getKey());
            if (r instanceof ShapelessRecipe s) Bukkit.removeRecipe(s.getKey());
            Bukkit.addRecipe(r);
        } catch (IllegalStateException e) {
            plugin.getLogger().warning("Could not register recipe: " + e.getMessage());
        }
    }

    public void unregisterRecipes() {
        for (var k : List.of(Keys.RECIPE_WAND, Keys.RECIPE_ROPE, Keys.RECIPE_BANNER, Keys.RECIPE_BOOK, Keys.RECIPE_BATON, Keys.RECIPE_MARKER, Keys.RECIPE_CHRISTMAS)) Bukkit.removeRecipe(k);
    }

    public void discover(Player p) {
        p.discoverRecipes(List.of(Keys.RECIPE_WAND, Keys.RECIPE_ROPE, Keys.RECIPE_BANNER, Keys.RECIPE_BOOK, Keys.RECIPE_BATON, Keys.RECIPE_MARKER, Keys.RECIPE_CHRISTMAS));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        discover(e.getPlayer());
    }

    /** Plugin items and colony goods can't be eaten by vanilla recipes (a wand is not a torch handle). */
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        Recipe r = e.getRecipe();
        boolean ours = r instanceof Keyed k && k.getKey().getNamespace().equals(Keys.RECIPE_WAND.getNamespace());
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (it == null || !it.hasItemMeta()) continue;
            var pdc = it.getItemMeta().getPersistentDataContainer();
            // our own recipes take colony goods, but never another plugin item (a War Banner is not "any banner")
            if (pdc.has(Keys.ITEM, PersistentDataType.STRING) || !ours && pdc.has(Keys.GOOD, PersistentDataType.STRING)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }
}

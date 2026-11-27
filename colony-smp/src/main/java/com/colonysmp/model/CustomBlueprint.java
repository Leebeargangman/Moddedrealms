package com.colonysmp.model;

import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A structure copied with the Colony Wand, for the Builders to build again. Blocks are kept in the copier's
 * frame: x to their right, z away from them (the side they stood on is the front), y up. Palette 0 is air.
 */
public final class CustomBlueprint {

    public final String name;
    public final int sx, sy, sz;
    /** Which way the copier looked: the copy's "forward". */
    public final BlockFace facing;
    /** 1 when the bottom layer is a floor/foundation that replaces the ground it's built on. */
    public final int sink;
    public final List<String> palette;
    public final int[] blocks;
    public String author = "";
    public long created;

    public CustomBlueprint(String name, int sx, int sy, int sz, BlockFace facing, int sink, List<String> palette, int[] blocks) {
        this.name = name;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.facing = facing;
        this.sink = sink;
        this.palette = List.copyOf(palette);
        this.blocks = blocks;
    }

    public static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public int index(int x, int y, int z) {
        return (y * sz + z) * sx + x;
    }

    public int at(int x, int y, int z) {
        return blocks[index(x, y, z)];
    }

    /** Blocks that aren't air. */
    public int solid() {
        int n = 0;
        for (int b : blocks) if (b != 0) n++;
        return n;
    }

    public String size() {
        return sx + "x" + sy + "x" + sz;
    }

    // ───────────── persistence ─────────────

    public void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("size", sx + "," + sy + "," + sz);
        s.set("facing", facing.name());
        s.set("sink", sink);
        s.set("palette", palette);
        s.set("data", encode(blocks));
        s.set("author", author);
        s.set("created", created);
    }

    public static CustomBlueprint load(ConfigurationSection s) {
        try {
            String[] sz = s.getString("size", "").split(",");
            int x = Integer.parseInt(sz[0]), y = Integer.parseInt(sz[1]), z = Integer.parseInt(sz[2]);
            List<String> pal = new ArrayList<>(s.getStringList("palette"));
            if (pal.isEmpty() || x <= 0 || y <= 0 || z <= 0) return null;
            int[] blocks = decode(s.getString("data", ""), x * y * z);
            if (blocks == null) return null;
            for (int b : blocks) if (b < 0 || b >= pal.size()) return null;
            CustomBlueprint cb = new CustomBlueprint(s.getString("name", "copy"), x, y, z,
                    BlockFace.valueOf(s.getString("facing", "NORTH")), s.getInt("sink"), pal, blocks);
            cb.author = s.getString("author", "");
            cb.created = s.getLong("created");
            return cb;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Run-length pairs as varints, deflated, base64. */
    private static String encode(int[] blocks) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        int i = 0;
        while (i < blocks.length) {
            int v = blocks[i], run = 1;
            while (i + run < blocks.length && blocks[i + run] == v) run++;
            varint(raw, v);
            varint(raw, run);
            i += run;
        }
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(raw.toByteArray());
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static int[] decode(String s, int size) {
        try {
            Inflater inf = new Inflater();
            inf.setInput(Base64.getDecoder().decode(s));
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                raw.write(buf, 0, n);
            }
            inf.end();
            byte[] b = raw.toByteArray();
            int[] out = new int[size];
            int[] pos = {0};
            int at = 0;
            while (pos[0] < b.length && at < size) {
                int v = readVarint(b, pos), run = readVarint(b, pos);
                for (int k = 0; k < run && at < size; k++) out[at++] = v;
            }
            return at == size ? out : null;
        } catch (IllegalArgumentException | DataFormatException e) {
            return null;
        }
    }

    private static void varint(ByteArrayOutputStream o, int v) {
        while ((v & ~0x7F) != 0) {
            o.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        o.write(v);
    }

    private static int readVarint(byte[] b, int[] pos) {
        int v = 0, shift = 0;
        while (pos[0] < b.length) {
            int x = b[pos[0]++] & 0xFF;
            v |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        return v;
    }
}

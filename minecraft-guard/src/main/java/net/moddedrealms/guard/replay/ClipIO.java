package net.moddedrealms.guard.replay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.Material;

/** Gzipped binary replay files (.mrr). */
public final class ClipIO {
    private static final int MAGIC = 0x4D525231; // "MRR1"

    private ClipIO() {}

    public static void write(Clip c, File file) throws IOException {
        Map<Material, Integer> palette = new HashMap<>();
        List<Material> order = new ArrayList<>();
        for (Clip.Track t : c.tracks) {
            for (Frame f : t.frames) {
                for (Material m : new Material[]{f.main, f.off, f.head, f.chest, f.legs, f.feet}) {
                    if (m != null && !palette.containsKey(m)) {
                        palette.put(m, order.size());
                        order.add(m);
                    }
                }
            }
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(tmp))))) {
            out.writeInt(MAGIC);
            out.writeUTF(c.id);
            out.writeUTF(c.subject.toString());
            out.writeUTF(c.subjectName);
            out.writeUTF(c.reason);
            out.writeLong(c.created);
            out.writeUTF(c.world);
            out.writeInt(c.length);
            out.writeInt(order.size());
            for (Material m : order) out.writeUTF(m.name());
            out.writeInt(c.tracks.size());
            for (Clip.Track t : c.tracks) {
                out.writeUTF(t.id.toString());
                out.writeUTF(t.type);
                out.writeUTF(t.name);
                out.writeBoolean(t.player);
                out.writeUTF(t.texture == null ? "" : t.texture);
                out.writeUTF(t.signature == null ? "" : t.signature);
                out.writeFloat(t.width);
                out.writeFloat(t.height);
                out.writeInt(t.frames.length);
                for (Frame f : t.frames) {
                    out.writeInt((int) f.tick);
                    out.writeDouble(f.x);
                    out.writeDouble(f.y);
                    out.writeDouble(f.z);
                    out.writeFloat(f.yaw);
                    out.writeFloat(f.pitch);
                    out.writeShort(f.flags);
                    for (Material m : new Material[]{f.main, f.off, f.head, f.chest, f.legs, f.feet}) {
                        out.writeShort(m == null ? -1 : palette.get(m));
                    }
                }
            }
            out.writeInt(c.blocks.size());
            for (Clip.BlockChange b : c.blocks) {
                out.writeInt(b.tick());
                out.writeInt(b.x());
                out.writeInt(b.y());
                out.writeInt(b.z());
                out.writeUTF(b.before());
                out.writeUTF(b.after());
            }
            out.writeInt(c.chat.size());
            for (Clip.ChatLine l : c.chat) {
                out.writeInt(l.tick());
                out.writeUTF(l.name());
                out.writeUTF(l.message().length() > 250 ? l.message().substring(0, 250) : l.message());
            }
        }
        if (!tmp.renameTo(file)) throw new IOException("could not rename " + tmp);
    }

    public static Clip read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            if (in.readInt() != MAGIC) throw new IOException("not a replay file");
            Clip c = new Clip();
            c.id = in.readUTF();
            c.subject = UUID.fromString(in.readUTF());
            c.subjectName = in.readUTF();
            c.reason = in.readUTF();
            c.created = in.readLong();
            c.world = in.readUTF();
            c.length = in.readInt();
            int pn = in.readInt();
            Material[] palette = new Material[pn];
            for (int i = 0; i < pn; i++) palette[i] = Material.matchMaterial(in.readUTF());
            int tn = in.readInt();
            for (int i = 0; i < tn; i++) {
                Clip.Track t = new Clip.Track();
                t.id = UUID.fromString(in.readUTF());
                t.type = in.readUTF();
                t.name = in.readUTF();
                t.player = in.readBoolean();
                t.texture = emptyToNull(in.readUTF());
                t.signature = emptyToNull(in.readUTF());
                t.width = in.readFloat();
                t.height = in.readFloat();
                int fn = in.readInt();
                t.frames = new Frame[fn];
                for (int j = 0; j < fn; j++) {
                    Frame f = new Frame();
                    f.tick = in.readInt();
                    f.x = in.readDouble();
                    f.y = in.readDouble();
                    f.z = in.readDouble();
                    f.yaw = in.readFloat();
                    f.pitch = in.readFloat();
                    f.flags = in.readShort();
                    f.main = mat(palette, in.readShort());
                    f.off = mat(palette, in.readShort());
                    f.head = mat(palette, in.readShort());
                    f.chest = mat(palette, in.readShort());
                    f.legs = mat(palette, in.readShort());
                    f.feet = mat(palette, in.readShort());
                    t.frames[j] = f;
                }
                c.tracks.add(t);
            }
            int bn = in.readInt();
            for (int i = 0; i < bn; i++) {
                c.blocks.add(new Clip.BlockChange(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readUTF(), in.readUTF()));
            }
            int cn = in.readInt();
            for (int i = 0; i < cn; i++) c.chat.add(new Clip.ChatLine(in.readInt(), in.readUTF(), in.readUTF()));
            return c;
        }
    }

    private static Material mat(Material[] palette, short idx) {
        return idx < 0 || idx >= palette.length ? null : palette[idx];
    }

    private static String emptyToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}

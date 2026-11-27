package net.moddedrealms.guard.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A saved replay: every entity near the subject, the blocks they changed and the chat, over a stretch of time. */
public final class Clip {
    public String id;
    public UUID subject;
    public String subjectName;
    public String reason;
    public long created;
    public String world;
    /** Length in ticks. */
    public int length;
    public final List<Track> tracks = new ArrayList<>();
    public final List<BlockChange> blocks = new ArrayList<>();
    public final List<ChatLine> chat = new ArrayList<>();

    public static final class Track {
        public UUID id;
        public String type;
        public String name;
        public String texture;
        public String signature;
        public float width, height;
        public boolean player;
        public Frame[] frames;

        /** The frame at this tick, or the closest one within 2 ticks; null if the entity wasn't there. */
        public Frame at(int tick) {
            int lo = 0, hi = frames.length - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                long t = frames[mid].tick;
                if (t < tick) lo = mid + 1;
                else if (t > tick) hi = mid - 1;
                else return frames[mid];
            }
            Frame best = null;
            if (hi >= 0 && tick - frames[hi].tick <= 2) best = frames[hi];
            if (lo < frames.length && frames[lo].tick - tick <= 2 && (best == null || frames[lo].tick - tick < tick - best.tick)) best = frames[lo];
            return best;
        }
    }

    public record BlockChange(int tick, int x, int y, int z, String before, String after) {}

    public record ChatLine(int tick, String name, String message) {}
}

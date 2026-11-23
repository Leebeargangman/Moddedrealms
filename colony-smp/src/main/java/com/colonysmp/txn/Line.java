package com.colonysmp.txn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A quantity of one kind of item in a transaction ("iron_ingot" × 12). */
public record Line(String good, long qty) {

    /** "iron_ingot*12;coal*3" */
    public static String format(List<Line> lines) {
        StringBuilder b = new StringBuilder();
        for (Line l : lines) {
            if (!b.isEmpty()) b.append(';');
            b.append(l.good).append('*').append(l.qty);
        }
        return b.toString();
    }

    public static List<Line> parse(String s) {
        List<Line> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(";")) {
            int i = part.lastIndexOf('*');
            if (i <= 0) continue;
            try {
                out.add(new Line(part.substring(0, i), Long.parseLong(part.substring(i + 1))));
            } catch (NumberFormatException ignored) {
                // a damaged row: the line is skipped, and the audit shows the raw text
            }
        }
        return out;
    }

    /** The same lines with duplicate goods added together, in first-seen order. */
    public static List<Line> merge(List<Line> lines) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Line l : lines) m.merge(l.good, l.qty, Long::sum);
        List<Line> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new Line(k, v)));
        return out;
    }

    public static String describe(List<Line> lines) {
        if (lines.isEmpty()) return "nothing";
        StringBuilder b = new StringBuilder();
        for (Line l : lines) {
            if (!b.isEmpty()) b.append(", ");
            b.append(l.qty).append(' ').append(l.good.replace('_', ' '));
        }
        return b.toString();
    }
}

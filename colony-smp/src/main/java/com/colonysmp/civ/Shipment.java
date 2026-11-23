package com.colonysmp.civ;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A caravan (or, between two harbours, a ship) on the road with real goods aboard. The goods left the sender's
 * stores when it set out and are in nobody's stores until it arrives: if it is robbed on the way, they are gone.
 * It carries its cargo out, barters it at the far end for what its home lacks, and carries that back.
 */
public final class Shipment {

    public enum Stage { OUTBOUND, RETURNING }

    public final String id;
    public String from, to;
    public Stage stage = Stage.OUTBOUND;
    public long departDay, arriveDay;
    public boolean bySea;
    public final Map<String, Long> cargo = new LinkedHashMap<>();

    public Shipment(String id) {
        this.id = id;
    }

    public double worth() {
        double v = 0;
        for (Map.Entry<String, Long> e : cargo.entrySet()) v += TradeGoods.worth(e.getKey()) * e.getValue();
        return v;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("to", to);
        m.put("stage", stage.name());
        m.put("depart", departDay);
        m.put("arrive", arriveDay);
        m.put("sea", bySea);
        m.put("cargo", new LinkedHashMap<>(cargo));
        return m;
    }

    public static Shipment fromMap(String id, Map<?, ?> m) {
        Shipment s = new Shipment(id);
        s.from = Leader.str(m.get("from"), "");
        s.to = Leader.str(m.get("to"), "");
        s.stage = "RETURNING".equals(Leader.str(m.get("stage"), "")) ? Stage.RETURNING : Stage.OUTBOUND;
        s.departDay = (long) Leader.num(m.get("depart"), 0);
        s.arriveDay = (long) Leader.num(m.get("arrive"), 0);
        s.bySea = Boolean.parseBoolean(Leader.str(m.get("sea"), "false"));
        if (m.get("cargo") instanceof Map<?, ?> cm) cm.forEach((k, v) -> s.cargo.put(String.valueOf(k), (long) Leader.num(v, 0)));
        return s;
    }
}

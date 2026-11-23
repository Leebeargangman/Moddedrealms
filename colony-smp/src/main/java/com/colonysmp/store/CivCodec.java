package com.colonysmp.store;

import com.colonysmp.civ.Civ;
import com.colonysmp.civ.CivWorld;
import com.colonysmp.civ.Contract;
import com.colonysmp.civ.Settlement;
import com.colonysmp.civ.Shipment;
import com.colonysmp.civ.War;
import com.colonysmp.core.Reservation;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the living world into rows for the colony database and back. Each civilisation, settlement, caravan,
 * contract and war is one YAML document; a settlement's document carries its stores and what is set aside in
 * them, so stores and reservations are always saved together.
 */
public final class CivCodec {

    private CivCodec() {}

    public static String encode(Map<String, Object> map) {
        return new Yaml().dump(map);
    }

    public static Map<?, ?> decode(String data) {
        Object o = new Yaml().load(data);
        return o instanceof Map<?, ?> m ? m : Map.of();
    }

    public static void snapshot(CivWorld w, Snapshot out) {
        out.civDocs.add(new Snapshot.CivDoc("meta", "world", encode(w.metaMap())));
        w.civs.forEach((id, c) -> out.civDocs.add(new Snapshot.CivDoc("civ", id, encode(c.toMap()))));
        w.settlements.forEach((id, s) -> {
            Map<String, Object> m = s.toMap();
            m.put("stock", new LinkedHashMap<>(s.stock.snapshot()));
            List<Map<String, Object>> holds = new ArrayList<>();
            for (Reservation r : s.stock.reservations()) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("id", r.id);
                h.put("owner", r.owner);
                h.put("created", r.created);
                h.put("goods", new LinkedHashMap<>(r.goods()));
                holds.add(h);
            }
            m.put("holds", holds);
            out.civDocs.add(new Snapshot.CivDoc("settlement", id, encode(m)));
        });
        w.shipments.forEach((id, s) -> out.civDocs.add(new Snapshot.CivDoc("shipment", id, encode(s.toMap()))));
        w.contracts.forEach((id, c) -> out.civDocs.add(new Snapshot.CivDoc("contract", id, encode(c.toMap()))));
        w.wars.forEach((id, x) -> out.civDocs.add(new Snapshot.CivDoc("war", id, encode(x.toMap()))));
    }

    public static CivWorld load(List<Snapshot.CivDoc> docs) {
        CivWorld w = new CivWorld();
        for (Snapshot.CivDoc d : docs) {
            Map<?, ?> m = decode(d.data());
            switch (d.kind()) {
                case "meta" -> w.loadMeta(m);
                case "civ" -> w.civs.put(d.id(), Civ.fromMap(d.id(), m));
                case "settlement" -> {
                    Settlement s = Settlement.fromMap(d.id(), m);
                    if (m.get("stock") instanceof Map<?, ?> st) st.forEach((k, v) -> s.stock.force(String.valueOf(k), ((Number) v).longValue()));
                    if (m.get("holds") instanceof List<?> hl) {
                        for (Object o : hl) {
                            if (!(o instanceof Map<?, ?> h)) continue;
                            Map<String, Long> goods = new LinkedHashMap<>();
                            if (h.get("goods") instanceof Map<?, ?> g) g.forEach((k, v) -> goods.put(String.valueOf(k), ((Number) v).longValue()));
                            s.stock.restore(String.valueOf(h.get("id")), String.valueOf(h.get("owner")), ((Number) h.get("created")).longValue(), goods);
                        }
                    }
                    s.stock.repair();
                    w.settlements.put(d.id(), s);
                }
                case "shipment" -> w.shipments.put(d.id(), Shipment.fromMap(d.id(), m));
                case "contract" -> w.contracts.put(d.id(), Contract.fromMap(d.id(), m));
                case "war" -> w.wars.put(d.id(), War.fromMap(d.id(), m));
                default -> {
                    // a kind from a newer version: left alone
                }
            }
        }
        for (String id : w.civs.keySet()) w.seenId(id);
        for (String id : w.settlements.keySet()) w.seenId(id);
        for (String id : w.shipments.keySet()) w.seenId(id);
        for (String id : w.contracts.keySet()) w.seenId(id);
        for (String id : w.wars.keySet()) w.seenId(id);
        return w;
    }
}

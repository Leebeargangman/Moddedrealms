package com.colonysmp.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything saved, as plain rows. Built on the server thread from the live objects, then written by the
 * database thread, so a save never sees half-changed state.
 */
public final class Snapshot {

    /** A YAML document keyed by colony (or "" for server-wide rows) and id. */
    public record Row(String colony, String id, String data) {}

    public record Member(String colony, String player, String role) {}

    public record Claim(String world, int cx, int cz, String colony) {}

    /** owner: "storage", "b12" (a building's local stock) or "c<uuid>" (carried by a citizen). */
    public record Stock(String colony, String owner, String good, long qty) {}

    public record Hold(String colony, String ledger, String id, String holder, long created, String goods) {}

    public record Item(String colony, int slot, byte[] item) {}

    public record TxnRow(String colony, long id, long time, long day, String type, long amount, long balance, String memo) {}

    public record EventRow(String colony, long id, long time, long day, String type, String message) {}

    public final List<Row> colonies = new ArrayList<>(), citizens = new ArrayList<>(), buildings = new ArrayList<>(),
            squads = new ArrayList<>(), orders = new ArrayList<>(), blueprints = new ArrayList<>(), relations = new ArrayList<>(),
            proposals = new ArrayList<>(), contracts = new ArrayList<>(), shipments = new ArrayList<>(), wars = new ArrayList<>();
    /** A document of the living world: kind "meta", "civ", "settlement", "shipment", "contract" or "war". */
    public record CivDoc(String kind, String id, String data) {}

    public final List<CivDoc> civDocs = new ArrayList<>();
    public final List<Member> members = new ArrayList<>();
    public final List<Claim> claims = new ArrayList<>();
    public final List<Stock> stock = new ArrayList<>();
    public final List<Hold> holds = new ArrayList<>();
    public final List<Item> equipment = new ArrayList<>();
    /** Only rows not written yet (appended). On load: the most recent rows per colony. */
    public final List<TxnRow> txns = new ArrayList<>();
    public final List<EventRow> events = new ArrayList<>();
    public final Map<String, Double> market = new HashMap<>();
    /** Colony ids that exist (logs of deleted colonies are pruned). */
    public final List<String> colonyIds = new ArrayList<>();

    public int size() {
        return civDocs.size() + colonies.size() + citizens.size() + buildings.size() + stock.size() + equipment.size() + txns.size() + events.size();
    }
}

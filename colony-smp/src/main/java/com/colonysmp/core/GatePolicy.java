package com.colonysmp.core;

/**
 * The rules a citizen follows at a fence gate, so that the animals behind it stay behind it. Plain numbers and
 * decisions, kept apart from the server so they can be tested on their own.
 *
 * <ul>
 *   <li>A gate is opened only from right beside it ({@link #inReach}), never from a distance.</li>
 *   <li>It is not opened while a loose animal stands within {@link #CLEAR_RADIUS} of it: the citizen waits and
 *       shoos them off for up to {@link #MAX_WAIT_TICKS}, then goes through without opening it at all.</li>
 *   <li>It shuts as soon as the citizen is through ({@link #shouldClose}), and at the latest after
 *       {@link #MAX_OPEN_TICKS}; it never stays open because an animal is standing in it.</li>
 *   <li>It shuts at once, in the citizen's face if need be, the moment a loose animal comes within
 *       {@link #GUARD_RADIUS} of it while it is open. An animal moves a fraction of a block a tick, so it cannot
 *       get from outside that circle to the far side of the gate before the gate is shut.</li>
 * </ul>
 */
public final class GatePolicy {

    /** No loose animal may be this close to a gate when it is opened (blocks). */
    public static final double CLEAR_RADIUS = 2.5;
    /** An open gate shuts the moment a loose animal comes this close to it (blocks). */
    public static final double GUARD_RADIUS = 1.6;
    /** How close a citizen has to be to open a gate (blocks, to its centre). */
    public static final double REACH = 1.6;
    /** How far past the gate's centre a citizen has to be before it shuts behind them. */
    public static final double THROUGH = 0.8;
    /** A gate is held open at least this long once opened, so it doesn't flap. */
    public static final long MIN_OPEN_TICKS = 3;
    /** However slowly its opener walks, a gate never stays open longer than this. */
    public static final long MAX_OPEN_TICKS = 30;
    /** How long a citizen waits for animals to leave a gate before slipping through without opening it. */
    public static final long MAX_WAIT_TICKS = 40;

    public enum Pass {
        /** Nothing in the way: open the gate and walk through. */
        OPEN,
        /** Animals at the gate: stand still and shoo them off. */
        WAIT,
        /** They won't move: go through without opening the gate. */
        SLIP
    }

    private GatePolicy() {}

    public static boolean inReach(double distanceSquared) {
        return distanceSquared <= REACH * REACH;
    }

    /** What a citizen standing at a closed gate does. */
    public static Pass approach(boolean animalsNear, long waitedTicks) {
        if (!animalsNear) return Pass.OPEN;
        return waitedTicks < MAX_WAIT_TICKS ? Pass.WAIT : Pass.SLIP;
    }

    /**
     * Should a gate its opener went through be shut now?
     *
     * @param openerDistanceSquared how far the opener is from the gate's centre (squared)
     * @param openTicks             how long it has been open
     * @param expired               its longest open time has passed
     * @param animalNear            a loose animal is within {@link #GUARD_RADIUS} of it
     */
    public static boolean shouldClose(double openerDistanceSquared, long openTicks, boolean expired, boolean animalNear) {
        if (animalNear || expired || openTicks >= MAX_OPEN_TICKS) return true;
        if (openTicks < MIN_OPEN_TICKS) return false;
        return openerDistanceSquared > THROUGH * THROUGH;
    }
}

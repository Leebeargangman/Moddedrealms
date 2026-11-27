package com.colonysmp.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules that keep animals behind a pen's gate, and a small model of a pen to show they do: a fenced square
 * of wandering animals, one gate, and a keeper who walks in and out all day. The model is run once with the
 * rules citizens used to follow (open the gate a block early, shut it later, and wait while anything stands in
 * the gateway) and once with {@link GatePolicy}. The old rules lose animals; the new ones must not lose one.
 */
class GatePolicyTest {

    @Test
    void aGateIsOnlyOpenedFromBesideIt() {
        assertTrue(GatePolicy.inReach(1.0));
        assertTrue(GatePolicy.inReach(GatePolicy.REACH * GatePolicy.REACH));
        assertFalse(GatePolicy.inReach(4.0));
    }

    @Test
    void animalsAtTheGateMakeTheKeeperWaitThenSlipThrough() {
        assertEquals(GatePolicy.Pass.OPEN, GatePolicy.approach(false, 0));
        assertEquals(GatePolicy.Pass.WAIT, GatePolicy.approach(true, 0));
        assertEquals(GatePolicy.Pass.WAIT, GatePolicy.approach(true, GatePolicy.MAX_WAIT_TICKS - 1));
        assertEquals(GatePolicy.Pass.SLIP, GatePolicy.approach(true, GatePolicy.MAX_WAIT_TICKS));
        // the moment they clear off, it opens
        assertEquals(GatePolicy.Pass.OPEN, GatePolicy.approach(false, 25));
    }

    @Test
    void theGateShutsAsSoonAsTheKeeperIsThroughAndNeverStaysOpen() {
        assertFalse(GatePolicy.shouldClose(0.0, 1, false, false), "not on the keeper's heels");
        assertFalse(GatePolicy.shouldClose(0.2, 8, false, false), "the keeper is still in the gateway");
        assertTrue(GatePolicy.shouldClose(1.0, 8, false, false), "the keeper is through");
        assertTrue(GatePolicy.shouldClose(0.0, GatePolicy.MAX_OPEN_TICKS, false, false), "however slow the keeper");
        assertTrue(GatePolicy.shouldClose(0.0, 5, true, false));
        assertTrue(GatePolicy.shouldClose(0.0, 0, false, true), "an animal coming up shuts it in the keeper's face");
    }

    // ───────────── the model pen ─────────────

    /** A 9×9 pen (cells 1..9), fence all round, a gate in the middle of the south side at (5, 0). Outside is y < 0. */
    private static final class Pen {
        static final int SIZE = 9, GATE_X = 5;
        final List<double[]> animals = new ArrayList<>();
        final Random rng;
        boolean gateOpen;
        int escaped;

        Pen(long seed, int herd) {
            rng = new Random(seed);
            for (int i = 0; i < herd; i++) animals.add(new double[]{1 + rng.nextInt(SIZE), 1 + rng.nextInt(SIZE)});
        }

        /** Animals drift a step at a time; the gate cell (y = 0) can only be entered while the gate is open. */
        void wander() {
            for (double[] a : animals) {
                if (a[1] < 0) continue; // already out
                if (rng.nextInt(4) != 0) continue;
                int dx = rng.nextInt(3) - 1, dy = rng.nextInt(3) - 1;
                double nx = a[0] + dx, ny = a[1] + dy;
                if (ny >= 1 && ny <= SIZE && nx >= 1 && nx <= SIZE) {
                    a[0] = nx;
                    a[1] = ny;
                } else if (gateOpen && nx == GATE_X && (ny == 0 || ny == -1)) {
                    a[0] = nx;
                    a[1] = ny;
                    if (ny < 0) escaped++;
                }
            }
        }

        boolean nearGate(double radius) {
            for (double[] a : animals) {
                if (a[1] < 0) continue;
                double dx = a[0] - GATE_X, dy = a[1];
                if (dx * dx + dy * dy <= radius * radius) return true;
            }
            return false;
        }

        boolean inGateway() {
            for (double[] a : animals) if (a[1] == 0 && a[0] == GATE_X) return true;
            return false;
        }

        /** Sends the animals at the gate towards the middle of the pen. */
        void shoo(double radius) {
            for (double[] a : animals) {
                if (a[1] < 0) continue;
                double dx = a[0] - GATE_X, dy = a[1];
                if (dx * dx + dy * dy <= radius * radius) a[1] = Math.min(SIZE, a[1] + 2);
            }
        }

        void putBackGateway() {
            for (double[] a : animals) if (a[1] == 0 && a[0] == GATE_X) a[1] = 2;
        }
    }

    /** The keeper walks from outside (y = -3) to inside (y = 3) or back at 0.13 blocks a tick, as citizens do. */
    private static int run(boolean newRules, long seed, int trips) {
        Pen pen = new Pen(seed, 10);
        double y = -3;
        int dir = 1;
        for (int trip = 0; trip < trips; trip++) {
            double target = dir > 0 ? 3 : -3;
            long openedAt = -1, closeAt = -1, waitSince = -1, tick = 0;
            boolean slip = false;
            while (Math.abs(y - target) > 0.05 && tick < 2000) {
                tick++;
                pen.wander();
                double dist = Math.abs(y);
                boolean atGate = dist <= (newRules ? GatePolicy.REACH : 2.0) && Math.signum(-y) == dir;
                boolean hold = false;
                if (!pen.gateOpen && atGate && !slip) {
                    if (newRules) {
                        boolean crowd = pen.nearGate(GatePolicy.CLEAR_RADIUS);
                        if (waitSince < 0) waitSince = tick;
                        switch (GatePolicy.approach(crowd, tick - waitSince)) {
                            case WAIT -> {
                                if ((tick - waitSince) % 10 == 0) pen.shoo(GatePolicy.CLEAR_RADIUS);
                                hold = true;
                            }
                            case SLIP -> slip = true;
                            default -> {
                                pen.gateOpen = true;
                                openedAt = tick;
                            }
                        }
                    } else {
                        // the old way: opened a block early, shut 15 ticks later at the soonest
                        pen.gateOpen = true;
                        openedAt = tick;
                        closeAt = tick + 15;
                    }
                }
                if (!hold) y += dir * 0.13;
                if (pen.gateOpen) {
                    if (newRules) {
                        if (GatePolicy.shouldClose(y * y, tick - openedAt, false, pen.nearGate(GatePolicy.GUARD_RADIUS))) {
                            pen.putBackGateway();
                            pen.gateOpen = false;
                        }
                    } else if (tick >= closeAt) {
                        // the old way: waited for as long as anything stood in the gateway
                        if (pen.inGateway() || Math.abs(y) < 0.7) closeAt = tick + 20;
                        else pen.gateOpen = false;
                    }
                }
            }
            // let anything still open run its course
            for (int i = 0; i < 200 && pen.gateOpen; i++) {
                pen.wander();
                if (newRules || !pen.inGateway()) {
                    if (newRules) pen.putBackGateway();
                    pen.gateOpen = false;
                }
            }
            dir = -dir;
        }
        return pen.escaped;
    }

    @Test
    void theOldGateRulesLoseAnimalsAndTheNewOnesLoseNone() {
        int old = 0, now = 0;
        for (long seed = 1; seed <= 20; seed++) {
            old += run(false, seed, 150);
            now += run(true, seed, 150);
        }
        assertTrue(old > 0, "the model should reproduce the escapes seen with the old rules (got " + old + ")");
        assertEquals(0, now, "no animal may get out past a keeper following GatePolicy");
    }
}

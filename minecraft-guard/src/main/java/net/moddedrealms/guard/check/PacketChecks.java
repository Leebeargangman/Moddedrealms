package net.moddedrealms.guard.check;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import java.util.Locale;
import net.moddedrealms.guard.GuardPlugin;
import net.moddedrealms.guard.PlayerState;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Checks that need the raw packets (netty threads). Each one looks for something a vanilla client cannot send:
 * a pitch past straight up/down, hitting itself, two different targets inside one client tick, or more game
 * ticks than real time allows.
 */
public final class PacketChecks {
    private final GuardPlugin plugin;
    private PacketListenerCommon listener;

    public PacketChecks(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        listener = PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract(PacketListenerPriority.MONITOR) {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                try {
                    handle(event);
                } catch (RuntimeException ex) {
                    // Never let a check break the connection.
                }
            }
        });
    }

    public void unregister() {
        if (listener != null) PacketEvents.getAPI().getEventManager().unregisterListener(listener);
    }

    private void handle(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        PlayerState s = plugin.stateIfPresent(p.getUniqueId());
        if (s == null) return;
        var type = event.getPacketType();

        if (type == PacketType.Play.Client.CLIENT_TICK_END) {
            ClientVersion v = event.getUser().getClientVersion();
            boolean modern = v != null && v.isNewerThanOrEquals(ClientVersion.V_1_21_2);
            s.modernClient = modern;
            if (modern) tickEnd(p, s);
            return;
        }
        if (type == PacketType.Play.Client.PLAYER_ROTATION) {
            pitch(p, new WrapperPlayClientPlayerRotation(event).getPitch());
            return;
        }
        if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            pitch(p, new WrapperPlayClientPlayerPositionAndRotation(event).getPitch());
            return;
        }
        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            var w = new WrapperPlayClientInteractEntity(event);
            if (w.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
            int target = w.getEntityId();
            if (target == p.getEntityId() && Check.BAD_PACKETS.enabled) {
                plugin.violations().flag(p, Check.BAD_PACKETS, true, "attacked itself");
                return;
            }
            if (s.modernClient && Check.MULTI_AURA.enabled) {
                int distinct;
                long gapNanos;
                synchronized (s) {
                    long now = System.nanoTime();
                    if (s.attackedThisTick.isEmpty()) s.firstAttackNanos = now;
                    s.attackedThisTick.add(target);
                    distinct = s.attackedThisTick.size();
                    gapNanos = now - s.firstAttackNanos;
                }
                // Also within 50ms of real time: a client (or ViaVersion) that skips a tick-end packet must not
                // make two ordinary hits look like one tick.
                if (distinct == 2 && gapNanos < 50_000_000L) {
                    plugin.violations().flag(p, Check.MULTI_AURA, true, "hit 2 different entities in one client tick");
                }
            }
            return;
        }
        if (type == PacketType.Play.Client.ANIMATION) {
            swing(p, s);
        }
    }

    private void pitch(Player p, float pitch) {
        if (Check.BAD_PACKETS.enabled && Math.abs(pitch) > 90.01f) {
            plugin.violations().flag(p, Check.BAD_PACKETS, true, String.format(Locale.ROOT, "impossible pitch %.1f", pitch));
        }
    }

    private void tickEnd(Player p, PlayerState s) {
        long now = System.nanoTime();
        boolean flag = false;
        double balance;
        synchronized (s) {
            s.attackedThisTick.clear();
            if (s.lastTickEndNanos != 0) {
                double gapMs = (now - s.lastTickEndNanos) / 1_000_000.0;
                if (gapMs > 2.0) {
                    // Packets arriving within 2ms of each other are a network flush, not faster ticking.
                    s.timerBalanceMs += 50.0 - gapMs;
                }
                if (s.timerBalanceMs < -3000) s.timerBalanceMs = -3000;
                if (s.timerBalanceMs > Check.TIMER.num("max-balance-ms", 2500)) {
                    flag = true;
                    s.timerBalanceMs = 0;
                }
            }
            s.lastTickEndNanos = now;
            balance = s.timerBalanceMs;
        }
        if (flag && Check.TIMER.enabled && p.getGameMode() != GameMode.SPECTATOR) {
            plugin.violations().flag(p, Check.TIMER, true, "client ran " + (int) Check.TIMER.num("max-balance-ms", 2500) + "ms of extra game time");
        }
    }

    private void swing(Player p, PlayerState s) {
        Check c = Check.AUTOCLICKER;
        if (!c.enabled) return;
        long ms = System.currentTimeMillis();
        int cps;
        boolean flag = false;
        synchronized (s) {
            s.swings.addLast(ms);
            while (!s.swings.isEmpty() && ms - s.swings.peekFirst() > 1000) s.swings.pollFirst();
            cps = s.swings.size();
            long second = ms / 1000;
            if (second != s.lastCpsSecond) {
                s.lastCpsSecond = second;
                if (cps > c.num("max-cps", 22)) {
                    s.cpsStreak++;
                    if (s.cpsStreak >= 5) {
                        flag = true;
                        s.cpsStreak = 0;
                    }
                } else {
                    s.cpsStreak = 0;
                }
            }
        }
        if (flag) plugin.violations().flag(p, c, false, cps + " clicks per second for 5 seconds");
    }
}

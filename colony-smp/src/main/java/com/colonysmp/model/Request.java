package com.colonysmp.model;

import java.util.UUID;

/**
 * Something a citizen has asked the colony's players for: a few of some good, wanted by a day. Whoever brings it
 * is thanked (and paid, if the citizen has money); the citizen is happier and works harder for a while.
 *
 * @param id       number shown to players
 * @param citizen  who asks
 * @param good     the good they want
 * @param amount   how many
 * @param reward   what they'll pay from their own purse (0 = thanks only)
 * @param reason   why they want it, in their words
 * @param untilDay the last day they'll wait
 */
public record Request(int id, UUID citizen, String good, int amount, long reward, String reason, long untilDay) {
}

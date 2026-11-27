package com.colonysmp.core;

/**
 * One recorded movement of money.
 *
 * @param amount  positive for money in, negative for money out
 * @param balance the treasury balance right after
 * @param day     the colony day it happened on
 */
public record Txn(long id, long time, long day, TxnType type, long amount, long balance, String memo) {
}

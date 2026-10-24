package io.github.vaibenz.orderbook;

/** Aggregated view of one price level, used for market depth snapshots. */
public record Level(long price, long quantity, int orders) {
}

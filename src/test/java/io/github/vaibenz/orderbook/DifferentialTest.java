package io.github.vaibenz.orderbook;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real book and the naive reference book with the same random command streams and
 * requires identical event logs (every accept, trade, rest, cancel, modify and reject, in order)
 * and identical final book state. Streams include invalid commands, duplicate ids, cancels of
 * filled orders and every order type, so edge cases get exercised far more than by hand.
 */
class DifferentialTest {

    private static final int SEEDS = 200;
    private static final int COMMANDS_PER_SEED = 2_000;
    private static final OrderType[] TYPES = OrderType.values();

    @Test
    void fastBookMatchesReferenceOnRandomStreams() {
        long totalTrades = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            totalTrades += runSeed(seed);
        }
        assertTrue(totalTrades > 10_000, "workload should produce plenty of trades, got " + totalTrades);
    }

    private long runSeed(long seed) {
        Random rnd = new Random(seed);
        RecordingListener fastEvents = new RecordingListener();
        RecordingListener refEvents = new RecordingListener();
        OrderBook fast = new OrderBook("DIFF", fastEvents);
        ReferenceOrderBook ref = new ReferenceOrderBook(refEvents);

        long nextId = 1;
        long trades = 0;
        for (int i = 0; i < COMMANDS_PER_SEED; i++) {
            int roll = rnd.nextInt(100);
            // Ids are drawn from a small recent window so cancels, modifies and duplicates hit
            // live orders, filled orders and unknown ids in realistic proportions.
            long targetId = Math.max(1, nextId - 1 - rnd.nextInt(40));

            if (roll < 60) {
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                OrderType type = TYPES[rnd.nextInt(TYPES.length)];
                long price = 95 + rnd.nextInt(11);
                long qty = 1 + rnd.nextInt(20);
                if (rnd.nextInt(50) == 0) {
                    qty = 0;
                }
                if (rnd.nextInt(50) == 0) {
                    price = 0;
                }
                long id = rnd.nextInt(40) == 0 ? targetId : nextId++;
                fast.submit(id, side, type, price, qty);
                ref.submit(id, side, type, price, qty);
            } else if (roll < 85) {
                fast.cancel(targetId);
                ref.cancel(targetId);
            } else {
                long price = 95 + rnd.nextInt(11);
                long qty = rnd.nextInt(25);
                fast.modify(targetId, price, qty);
                ref.modify(targetId, price, qty);
            }

            assertEquals(refEvents.events, fastEvents.events, "seed " + seed + " diverged at command " + i);
            trades += fastEvents.trades.size();
            fastEvents.clear();
            refEvents.clear();
        }

        assertEquals(ref.levels(Side.BUY), levels(fast, Side.BUY), "bids, seed " + seed);
        assertEquals(ref.levels(Side.SELL), levels(fast, Side.SELL), "asks, seed " + seed);
        assertEquals(ref.size(), fast.restingOrderCount(), "order count, seed " + seed);
        return trades;
    }

    private static TreeMap<Long, Long> levels(OrderBook book, Side side) {
        TreeMap<Long, Long> out = new TreeMap<>();
        for (Level level : book.depth(side, Integer.MAX_VALUE)) {
            out.put(level.price(), level.quantity());
        }
        return out;
    }
}

package io.github.vaibenz.orderbook.bench;

import io.github.vaibenz.orderbook.OrderBook;
import io.github.vaibenz.orderbook.OrderBookListener;
import io.github.vaibenz.orderbook.OrderType;
import io.github.vaibenz.orderbook.Side;
import io.github.vaibenz.orderbook.Trade;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Throughput and latency benchmark on a realistic, replayable order flow.
 *
 * <p>The workload is generated once up front (so random number generation is not timed) by
 * driving a scratch book with a random walk mid price. The mix is roughly 55% new limit orders
 * near the touch, 25% cancels of live orders, 10% modifies, 7% IOC and 3% market orders.
 * The same command stream is then replayed against fresh books.
 *
 * <pre>
 *   java -cp target/classes io.github.vaibenz.orderbook.bench.Benchmark [commands] [seed]
 * </pre>
 */
public final class Benchmark {

    private static final byte SUBMIT = 0, CANCEL = 1, MODIFY = 2;

    private final byte[] kind;
    private final long[] id;
    private final Side[] side;
    private final OrderType[] type;
    private final long[] price;
    private final long[] qty;

    private Benchmark(int n, long seed) {
        kind = new byte[n];
        id = new long[n];
        side = new Side[n];
        type = new OrderType[n];
        price = new long[n];
        qty = new long[n];
        generate(n, seed);
    }

    private void generate(int n, long seed) {
        Random rnd = new Random(seed);
        OrderBook scratch = new OrderBook("GEN");
        ArrayList<Long> submitted = new ArrayList<>();
        long mid = 10_000;
        long nextId = 1;

        for (int i = 0; i < n; i++) {
            if (rnd.nextInt(100) < 5) {
                mid = Math.max(100, mid + rnd.nextInt(3) - 1);
            }
            int roll = rnd.nextInt(100);
            Long target = roll >= 55 && roll < 90 ? pickLive(scratch, submitted, rnd) : null;

            if (target != null && roll < 80) {
                kind[i] = CANCEL;
                id[i] = target;
                scratch.cancel(target);
            } else if (target != null) {
                var o = scratch.order(target);
                long newPrice = rnd.nextBoolean() ? o.price() : passivePrice(o.side(), mid, rnd);
                long newQty = 1 + rnd.nextInt(100);
                kind[i] = MODIFY;
                id[i] = target;
                price[i] = newPrice;
                qty[i] = newQty;
                scratch.modify(target, newPrice, newQty);
            } else {
                Side s = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                OrderType t;
                long p;
                if (roll >= 97) {
                    t = OrderType.MARKET;
                    p = 0;
                } else if (roll >= 90) {
                    t = OrderType.IOC;
                    p = aggressivePrice(s, mid, rnd);
                } else {
                    t = OrderType.LIMIT;
                    p = rnd.nextInt(10) == 0 ? aggressivePrice(s, mid, rnd) : passivePrice(s, mid, rnd);
                }
                long q = 1 + rnd.nextInt(100);
                long oid = nextId++;
                kind[i] = SUBMIT;
                id[i] = oid;
                side[i] = s;
                type[i] = t;
                price[i] = p;
                qty[i] = q;
                scratch.submit(oid, s, t, p, q);
                if (t == OrderType.LIMIT) {
                    submitted.add(oid);
                }
            }
        }
    }

    private static Long pickLive(OrderBook book, ArrayList<Long> ids, Random rnd) {
        for (int tries = 0; tries < 8 && !ids.isEmpty(); tries++) {
            int idx = rnd.nextInt(ids.size());
            long candidate = ids.get(idx);
            if (book.order(candidate) != null) {
                return candidate;
            }
            ids.set(idx, ids.get(ids.size() - 1));
            ids.remove(ids.size() - 1);
        }
        return null;
    }

    private static long passivePrice(Side s, long mid, Random rnd) {
        long offset = 1 + rnd.nextInt(20);
        return s == Side.BUY ? mid - offset : mid + offset;
    }

    private static long aggressivePrice(Side s, long mid, Random rnd) {
        long offset = rnd.nextInt(5);
        return s == Side.BUY ? mid + offset : mid - offset;
    }

    /** Replays the whole stream on a fresh book and returns elapsed nanoseconds. */
    private long replay(OrderBookListener listener) {
        OrderBook book = new OrderBook("BENCH", listener);
        long start = System.nanoTime();
        for (int i = 0; i < kind.length; i++) {
            apply(book, i);
        }
        return System.nanoTime() - start;
    }

    /** Replays the stream timing each command individually. */
    private long[] replayTimed(OrderBookListener listener) {
        OrderBook book = new OrderBook("BENCH", listener);
        long[] lat = new long[kind.length];
        for (int i = 0; i < kind.length; i++) {
            long t0 = System.nanoTime();
            apply(book, i);
            lat[i] = System.nanoTime() - t0;
        }
        return lat;
    }

    private void apply(OrderBook book, int i) {
        switch (kind[i]) {
            case SUBMIT -> book.submit(id[i], side[i], type[i], price[i], qty[i]);
            case CANCEL -> book.cancel(id[i]);
            default -> book.modify(id[i], price[i], qty[i]);
        }
    }

    /** Counts trades so the JIT cannot discard the event path. */
    private static final class CountingListener implements OrderBookListener {
        long trades;
        long volume;

        @Override
        public void onTrade(Trade trade) {
            trades++;
            volume += trade.quantity();
        }
    }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 2_000_000;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 42L;

        System.out.printf(Locale.ROOT, "Generating %,d commands (seed %d)...%n", n, seed);
        Benchmark b = new Benchmark(n, seed);

        int submits = 0, cancels = 0, modifies = 0;
        for (byte k : b.kind) {
            if (k == SUBMIT) submits++;
            else if (k == CANCEL) cancels++;
            else modifies++;
        }
        System.out.printf(Locale.ROOT, "Mix: %,d submits, %,d cancels, %,d modifies%n", submits, cancels, modifies);

        for (int w = 0; w < 5; w++) {
            b.replay(new CountingListener());
        }

        int runs = 5;
        double[] mops = new double[runs];
        CountingListener counter = null;
        for (int r = 0; r < runs; r++) {
            counter = new CountingListener();
            long ns = b.replay(counter);
            mops[r] = n / (ns / 1e9) / 1e6;
        }
        Arrays.sort(mops);
        System.out.printf(Locale.ROOT, "Trades per run: %,d (volume %,d lots)%n", counter.trades, counter.volume);
        System.out.printf(Locale.ROOT, "Throughput: median %.2f M commands/s (min %.2f, max %.2f) over %d runs%n",
                mops[runs / 2], mops[0], mops[runs - 1], runs);

        for (int w = 0; w < 2; w++) {
            b.replayTimed(new CountingListener());
        }
        long[] lat = b.replayTimed(new CountingListener());
        Arrays.sort(lat);
        System.out.printf(Locale.ROOT, "Latency per command: p50 %d ns, p99 %d ns, p99.9 %d ns, max %,d ns%n",
                pct(lat, 50), pct(lat, 99), pct(lat, 99.9), lat[lat.length - 1]);
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version")
                + ", cores: " + Runtime.getRuntime().availableProcessors());
    }

    private static long pct(long[] sorted, double p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }
}

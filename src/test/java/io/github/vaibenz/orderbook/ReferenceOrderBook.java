package io.github.vaibenz.orderbook;

import io.github.vaibenz.orderbook.OrderBookListener.CancelReason;
import io.github.vaibenz.orderbook.OrderBookListener.RejectReason;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Deliberately naive order book used as a test oracle. Every resting order lives in one flat
 * list and every match does a linear scan for the best counterparty. It is far too slow for
 * real use but simple enough to be obviously correct, so the fast book must agree with it.
 */
final class ReferenceOrderBook {

    private static final class Resting {
        final long id;
        final Side side;
        long price;
        long remaining;
        long seq;

        Resting(long id, Side side, long price, long remaining, long seq) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.remaining = remaining;
            this.seq = seq;
        }
    }

    private final List<Resting> resting = new ArrayList<>();
    private final OrderBookListener listener;
    private long seq;

    ReferenceOrderBook(OrderBookListener listener) {
        this.listener = listener;
    }

    void submit(long id, Side side, OrderType type, long price, long qty) {
        if (qty <= 0) {
            listener.onRejected(id, RejectReason.INVALID_QUANTITY);
            return;
        }
        if (type != OrderType.MARKET && price <= 0) {
            listener.onRejected(id, RejectReason.INVALID_PRICE);
            return;
        }
        if (find(id) != null) {
            listener.onRejected(id, RejectReason.DUPLICATE_ORDER_ID);
            return;
        }
        if (type == OrderType.POST_ONLY) {
            Resting best = best(side.opposite());
            if (best != null && crosses(side, price, best.price)) {
                listener.onRejected(id, RejectReason.POST_ONLY_WOULD_TAKE);
                return;
            }
        }
        long limit = type == OrderType.MARKET ? (side == Side.BUY ? Long.MAX_VALUE : Long.MIN_VALUE) : price;
        listener.onAccepted(id, side, type, type == OrderType.MARKET ? 0 : price, qty);

        if (type == OrderType.FOK) {
            long available = 0;
            for (Resting r : resting) {
                if (r.side != side && crosses(side, limit, r.price)) {
                    available += r.remaining;
                }
            }
            if (available < qty) {
                listener.onCancelled(id, qty, CancelReason.FILL_OR_KILL_FAILED);
                return;
            }
        }

        long remaining = match(id, side, limit, qty);
        if (remaining > 0) {
            if (type == OrderType.LIMIT || type == OrderType.POST_ONLY) {
                resting.add(new Resting(id, side, price, remaining, seq++));
                listener.onRested(id, side, price, remaining);
            } else {
                listener.onCancelled(id, remaining, CancelReason.UNFILLED_REMAINDER);
            }
        }
    }

    void cancel(long id) {
        Resting r = find(id);
        if (r == null) {
            listener.onRejected(id, RejectReason.UNKNOWN_ORDER_ID);
            return;
        }
        resting.remove(r);
        listener.onCancelled(id, r.remaining, CancelReason.USER_REQUESTED);
    }

    void modify(long id, long newPrice, long newQty) {
        Resting r = find(id);
        if (r == null) {
            listener.onRejected(id, RejectReason.UNKNOWN_ORDER_ID);
            return;
        }
        if (newQty <= 0) {
            listener.onRejected(id, RejectReason.INVALID_QUANTITY);
            return;
        }
        if (newPrice <= 0) {
            listener.onRejected(id, RejectReason.INVALID_PRICE);
            return;
        }
        if (newPrice == r.price && newQty <= r.remaining) {
            r.remaining = newQty;
            listener.onModified(id, newPrice, newQty);
            return;
        }
        resting.remove(r);
        listener.onModified(id, newPrice, newQty);
        long remaining = match(id, r.side, newPrice, newQty);
        if (remaining > 0) {
            r.price = newPrice;
            r.remaining = remaining;
            r.seq = seq++;
            resting.add(r);
            listener.onRested(id, r.side, newPrice, remaining);
        }
    }

    /** Price to total quantity on one side, for comparing final state with the real book. */
    TreeMap<Long, Long> levels(Side side) {
        TreeMap<Long, Long> out = new TreeMap<>();
        for (Resting r : resting) {
            if (r.side == side) {
                out.merge(r.price, r.remaining, Long::sum);
            }
        }
        return out;
    }

    int size() {
        return resting.size();
    }

    private long match(long takerId, Side side, long limit, long qty) {
        long remaining = qty;
        while (remaining > 0) {
            Resting maker = best(side.opposite());
            if (maker == null || !crosses(side, limit, maker.price)) {
                break;
            }
            long fill = Math.min(remaining, maker.remaining);
            remaining -= fill;
            maker.remaining -= fill;
            if (maker.remaining == 0) {
                resting.remove(maker);
            }
            listener.onTrade(new Trade(takerId, maker.id, side, maker.price, fill));
        }
        return remaining;
    }

    /** Best resting order on a side by price, then by arrival sequence. */
    private Resting best(Side side) {
        Resting best = null;
        for (Resting r : resting) {
            if (r.side != side) {
                continue;
            }
            if (best == null) {
                best = r;
                continue;
            }
            boolean betterPrice = side == Side.BUY ? r.price > best.price : r.price < best.price;
            if (betterPrice || (r.price == best.price && r.seq < best.seq)) {
                best = r;
            }
        }
        return best;
    }

    private Resting find(long id) {
        for (Resting r : resting) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    private static boolean crosses(Side takerSide, long limit, long restingPrice) {
        return takerSide == Side.BUY ? restingPrice <= limit : restingPrice >= limit;
    }
}

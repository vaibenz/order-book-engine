package io.github.vaibenz.orderbook;

import io.github.vaibenz.orderbook.OrderBookListener.CancelReason;
import io.github.vaibenz.orderbook.OrderBookListener.RejectReason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Single instrument limit order book with price time priority matching.
 *
 * <p>Prices and quantities are integers (price in ticks, quantity in lots) so matching never
 * touches floating point. Each side is a sorted map from price to a FIFO {@link PriceLevel},
 * and every resting order is indexed by id for O(1) lookup and O(1) unlinking on cancel.
 *
 * <p>The book is single threaded by design, like the matching core of most exchanges: callers
 * serialise commands onto one thread and the book emits events synchronously to its listener.
 */
public final class OrderBook {

    private final String symbol;
    private final OrderBookListener listener;

    /** Bids sorted highest price first. */
    private final NavigableMap<Long, PriceLevel> bids = new TreeMap<>(Collections.reverseOrder());
    /** Asks sorted lowest price first. */
    private final NavigableMap<Long, PriceLevel> asks = new TreeMap<>();
    private final Map<Long, Order> ordersById = new HashMap<>();

    private long nextSequence;

    public OrderBook(String symbol) {
        this(symbol, OrderBookListener.NO_OP);
    }

    public OrderBook(String symbol, OrderBookListener listener) {
        this.symbol = symbol;
        this.listener = listener;
    }

    public String symbol() {
        return symbol;
    }

    // ------------------------------------------------------------------ commands

    /**
     * Submits a new order. For {@link OrderType#MARKET} the price argument is ignored.
     *
     * @return quantity filled immediately
     */
    public long submit(long orderId, Side side, OrderType type, long price, long quantity) {
        if (quantity <= 0) {
            listener.onRejected(orderId, RejectReason.INVALID_QUANTITY);
            return 0;
        }
        if (type.hasPrice() && price <= 0) {
            listener.onRejected(orderId, RejectReason.INVALID_PRICE);
            return 0;
        }
        if (ordersById.containsKey(orderId)) {
            listener.onRejected(orderId, RejectReason.DUPLICATE_ORDER_ID);
            return 0;
        }
        if (type == OrderType.POST_ONLY && wouldCross(side, price)) {
            listener.onRejected(orderId, RejectReason.POST_ONLY_WOULD_TAKE);
            return 0;
        }

        long limit = type.hasPrice() ? price : (side == Side.BUY ? Long.MAX_VALUE : Long.MIN_VALUE);
        listener.onAccepted(orderId, side, type, type.hasPrice() ? price : 0, quantity);

        if (type == OrderType.FOK && availableToTake(side, limit, quantity) < quantity) {
            listener.onCancelled(orderId, quantity, CancelReason.FILL_OR_KILL_FAILED);
            return 0;
        }

        long remaining = match(orderId, side, limit, quantity);
        long filled = quantity - remaining;

        if (remaining > 0) {
            if (type.canRest()) {
                rest(new Order(orderId, side, price, remaining, nextSequence++));
            } else {
                listener.onCancelled(orderId, remaining, CancelReason.UNFILLED_REMAINDER);
            }
        }
        return filled;
    }

    /** Cancels a resting order. Returns false if no such order is resting. */
    public boolean cancel(long orderId) {
        Order order = ordersById.remove(orderId);
        if (order == null) {
            listener.onRejected(orderId, RejectReason.UNKNOWN_ORDER_ID);
            return false;
        }
        long cancelled = order.remaining;
        unlink(order);
        listener.onCancelled(orderId, cancelled, CancelReason.USER_REQUESTED);
        return true;
    }

    /**
     * Modifies a resting order to a new price and new remaining quantity.
     *
     * <p>Reducing quantity at the same price keeps the order's queue position. Any price change or
     * quantity increase loses priority: the order is pulled and re-entered as a fresh limit order,
     * which may trade immediately if the new price crosses the spread.
     *
     * @return false if the order is unknown or the new values are invalid
     */
    public boolean modify(long orderId, long newPrice, long newQuantity) {
        Order order = ordersById.get(orderId);
        if (order == null) {
            listener.onRejected(orderId, RejectReason.UNKNOWN_ORDER_ID);
            return false;
        }
        if (newQuantity <= 0) {
            listener.onRejected(orderId, RejectReason.INVALID_QUANTITY);
            return false;
        }
        if (newPrice <= 0) {
            listener.onRejected(orderId, RejectReason.INVALID_PRICE);
            return false;
        }

        if (newPrice == order.price && newQuantity <= order.remaining) {
            order.level.reduce(order, order.remaining - newQuantity);
            listener.onModified(orderId, newPrice, newQuantity);
            return true;
        }

        ordersById.remove(orderId);
        unlink(order);
        listener.onModified(orderId, newPrice, newQuantity);

        long remaining = match(orderId, order.side, newPrice, newQuantity);
        if (remaining > 0) {
            order.price = newPrice;
            order.remaining = remaining;
            order.sequence = nextSequence++;
            rest(order);
        }
        return true;
    }

    // ------------------------------------------------------------------ queries

    /** Best bid price, or 0 if there are no bids. */
    public long bestBid() {
        return bids.isEmpty() ? 0 : bids.firstKey();
    }

    /** Best ask price, or 0 if there are no asks. */
    public long bestAsk() {
        return asks.isEmpty() ? 0 : asks.firstKey();
    }

    /** Total resting quantity at a price on one side. */
    public long quantityAt(Side side, long price) {
        PriceLevel level = book(side).get(price);
        return level == null ? 0 : level.totalQuantity;
    }

    /** Top {@code levels} price levels on one side, best first. */
    public List<Level> depth(Side side, int levels) {
        List<Level> out = new ArrayList<>(Math.min(levels, book(side).size()));
        for (PriceLevel level : book(side).values()) {
            if (out.size() == levels) {
                break;
            }
            out.add(new Level(level.price, level.totalQuantity, level.orderCount));
        }
        return out;
    }

    /** Resting orders at one price, in queue (time priority) order. */
    public List<Order> ordersAt(Side side, long price) {
        List<Order> out = new ArrayList<>();
        PriceLevel level = book(side).get(price);
        for (Order o = level == null ? null : level.head; o != null; o = o.next) {
            out.add(o);
        }
        return out;
    }

    public Order order(long orderId) {
        return ordersById.get(orderId);
    }

    public int restingOrderCount() {
        return ordersById.size();
    }

    public int levelCount(Side side) {
        return book(side).size();
    }

    // ------------------------------------------------------------------ internals

    /**
     * Matches an incoming order against the opposite side up to {@code limit}.
     * Trades execute at the resting order's price. Returns the unfilled quantity.
     */
    private long match(long takerId, Side takerSide, long limit, long quantity) {
        NavigableMap<Long, PriceLevel> opposite = book(takerSide.opposite());
        long remaining = quantity;

        while (remaining > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, PriceLevel> best = opposite.firstEntry();
            long levelPrice = best.getKey();
            if (!crosses(takerSide, limit, levelPrice)) {
                break;
            }
            PriceLevel level = best.getValue();

            Order maker = level.head;
            while (remaining > 0 && maker != null) {
                long fill = Math.min(remaining, maker.remaining);
                remaining -= fill;
                Order nextMaker = maker.next;

                if (fill == maker.remaining) {
                    level.remove(maker);
                    ordersById.remove(maker.id);
                } else {
                    level.reduce(maker, fill);
                }
                listener.onTrade(new Trade(takerId, maker.id, takerSide, levelPrice, fill));
                maker = nextMaker;
            }

            if (level.isEmpty()) {
                opposite.pollFirstEntry();
            }
        }
        return remaining;
    }

    /** Quantity an incoming order could take right now, capped at {@code wanted}. */
    private long availableToTake(Side takerSide, long limit, long wanted) {
        long available = 0;
        Iterator<PriceLevel> it = book(takerSide.opposite()).values().iterator();
        while (it.hasNext() && available < wanted) {
            PriceLevel level = it.next();
            if (!crosses(takerSide, limit, level.price)) {
                break;
            }
            available += level.totalQuantity;
        }
        return available;
    }

    private boolean wouldCross(Side side, long price) {
        NavigableMap<Long, PriceLevel> opposite = book(side.opposite());
        return !opposite.isEmpty() && crosses(side, price, opposite.firstKey());
    }

    private static boolean crosses(Side takerSide, long limit, long restingPrice) {
        return takerSide == Side.BUY ? restingPrice <= limit : restingPrice >= limit;
    }

    private void rest(Order order) {
        book(order.side).computeIfAbsent(order.price, PriceLevel::new).append(order);
        ordersById.put(order.id, order);
        listener.onRested(order.id, order.side, order.price, order.remaining);
    }

    private void unlink(Order order) {
        PriceLevel level = order.level;
        level.remove(order);
        if (level.isEmpty()) {
            book(order.side).remove(level.price);
        }
    }

    private NavigableMap<Long, PriceLevel> book(Side side) {
        return side == Side.BUY ? bids : asks;
    }
}

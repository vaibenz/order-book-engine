package io.github.vaibenz.orderbook;

/**
 * Receives every event the book produces, in the order it produces them.
 * All methods default to no ops so callers only override what they need.
 */
public interface OrderBookListener {

    enum RejectReason {
        INVALID_QUANTITY,
        INVALID_PRICE,
        DUPLICATE_ORDER_ID,
        UNKNOWN_ORDER_ID,
        POST_ONLY_WOULD_TAKE
    }

    enum CancelReason {
        USER_REQUESTED,
        /** Unfilled remainder of a MARKET or IOC order. */
        UNFILLED_REMAINDER,
        /** FOK order that could not be filled in full. */
        FILL_OR_KILL_FAILED
    }

    default void onAccepted(long orderId, Side side, OrderType type, long price, long quantity) {
    }

    default void onTrade(Trade trade) {
    }

    default void onRested(long orderId, Side side, long price, long remaining) {
    }

    default void onCancelled(long orderId, long cancelledQuantity, CancelReason reason) {
    }

    default void onModified(long orderId, long newPrice, long newRemaining) {
    }

    default void onRejected(long orderId, RejectReason reason) {
    }

    OrderBookListener NO_OP = new OrderBookListener() {
    };
}

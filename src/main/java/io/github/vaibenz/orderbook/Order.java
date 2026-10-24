package io.github.vaibenz.orderbook;

/**
 * A resting order. Orders are nodes of an intrusive doubly linked list inside their
 * {@link PriceLevel}, which makes cancelling any order O(1) once it is found in the id index.
 */
public final class Order {
    final long id;
    final Side side;
    long price;
    long remaining;
    long sequence;

    PriceLevel level;
    Order prev;
    Order next;

    Order(long id, Side side, long price, long remaining, long sequence) {
        this.id = id;
        this.side = side;
        this.price = price;
        this.remaining = remaining;
        this.sequence = sequence;
    }

    public long id() {
        return id;
    }

    public Side side() {
        return side;
    }

    public long price() {
        return price;
    }

    public long remaining() {
        return remaining;
    }

    /** Arrival sequence number. Lower means earlier in the queue at the same price. */
    public long sequence() {
        return sequence;
    }
}

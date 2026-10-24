package io.github.vaibenz.orderbook;

/** FIFO queue of orders at a single price, kept as an intrusive doubly linked list. */
final class PriceLevel {
    final long price;
    Order head;
    Order tail;
    long totalQuantity;
    int orderCount;

    PriceLevel(long price) {
        this.price = price;
    }

    void append(Order order) {
        order.level = this;
        order.prev = tail;
        order.next = null;
        if (tail == null) {
            head = order;
        } else {
            tail.next = order;
        }
        tail = order;
        totalQuantity += order.remaining;
        orderCount++;
    }

    void remove(Order order) {
        if (order.prev == null) {
            head = order.next;
        } else {
            order.prev.next = order.next;
        }
        if (order.next == null) {
            tail = order.prev;
        } else {
            order.next.prev = order.prev;
        }
        totalQuantity -= order.remaining;
        orderCount--;
        order.prev = null;
        order.next = null;
        order.level = null;
    }

    /** Reduces an order's quantity in place, keeping its place in the queue. */
    void reduce(Order order, long by) {
        order.remaining -= by;
        totalQuantity -= by;
    }

    boolean isEmpty() {
        return head == null;
    }
}

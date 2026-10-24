package io.github.vaibenz.orderbook;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Routes commands to one {@link OrderBook} per symbol. */
public final class MatchingEngine {

    private final Map<String, OrderBook> books = new HashMap<>();
    private final Function<String, OrderBookListener> listenerFactory;

    public MatchingEngine() {
        this(symbol -> OrderBookListener.NO_OP);
    }

    public MatchingEngine(Function<String, OrderBookListener> listenerFactory) {
        this.listenerFactory = listenerFactory;
    }

    public OrderBook book(String symbol) {
        return books.computeIfAbsent(symbol, s -> new OrderBook(s, listenerFactory.apply(s)));
    }

    public long submit(String symbol, long orderId, Side side, OrderType type, long price, long quantity) {
        return book(symbol).submit(orderId, side, type, price, quantity);
    }

    public boolean cancel(String symbol, long orderId) {
        OrderBook book = books.get(symbol);
        return book != null && book.cancel(orderId);
    }

    public boolean modify(String symbol, long orderId, long newPrice, long newQuantity) {
        OrderBook book = books.get(symbol);
        return book != null && book.modify(orderId, newPrice, newQuantity);
    }
}

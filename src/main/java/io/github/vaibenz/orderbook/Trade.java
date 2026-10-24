package io.github.vaibenz.orderbook;

/**
 * A single fill between an incoming (taker) order and a resting (maker) order.
 * Trades always execute at the maker's price.
 */
public record Trade(long takerOrderId, long makerOrderId, Side takerSide, long price, long quantity) {

    public long buyOrderId() {
        return takerSide == Side.BUY ? takerOrderId : makerOrderId;
    }

    public long sellOrderId() {
        return takerSide == Side.SELL ? takerOrderId : makerOrderId;
    }
}

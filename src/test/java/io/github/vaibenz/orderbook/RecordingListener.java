package io.github.vaibenz.orderbook;

import java.util.ArrayList;
import java.util.List;

/** Records every event as a compact string so two books can be compared event by event. */
final class RecordingListener implements OrderBookListener {

    final List<String> events = new ArrayList<>();
    final List<Trade> trades = new ArrayList<>();

    @Override
    public void onAccepted(long orderId, Side side, OrderType type, long price, long quantity) {
        events.add("ACCEPT " + orderId + " " + side + " " + type + " " + price + " " + quantity);
    }

    @Override
    public void onTrade(Trade trade) {
        trades.add(trade);
        events.add("TRADE " + trade.takerOrderId() + " " + trade.makerOrderId() + " " + trade.takerSide()
                + " " + trade.price() + " " + trade.quantity());
    }

    @Override
    public void onRested(long orderId, Side side, long price, long remaining) {
        events.add("REST " + orderId + " " + side + " " + price + " " + remaining);
    }

    @Override
    public void onCancelled(long orderId, long cancelledQuantity, CancelReason reason) {
        events.add("CANCEL " + orderId + " " + cancelledQuantity + " " + reason);
    }

    @Override
    public void onModified(long orderId, long newPrice, long newRemaining) {
        events.add("MODIFY " + orderId + " " + newPrice + " " + newRemaining);
    }

    @Override
    public void onRejected(long orderId, RejectReason reason) {
        events.add("REJECT " + orderId + " " + reason);
    }

    void clear() {
        events.clear();
        trades.clear();
    }
}

package io.github.vaibenz.orderbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.vaibenz.orderbook.OrderBookListener.CancelReason.FILL_OR_KILL_FAILED;
import static io.github.vaibenz.orderbook.OrderBookListener.CancelReason.UNFILLED_REMAINDER;
import static io.github.vaibenz.orderbook.OrderType.FOK;
import static io.github.vaibenz.orderbook.OrderType.IOC;
import static io.github.vaibenz.orderbook.OrderType.LIMIT;
import static io.github.vaibenz.orderbook.OrderType.MARKET;
import static io.github.vaibenz.orderbook.OrderType.POST_ONLY;
import static io.github.vaibenz.orderbook.Side.BUY;
import static io.github.vaibenz.orderbook.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookTest {

    private RecordingListener events;
    private OrderBook book;

    @BeforeEach
    void setUp() {
        events = new RecordingListener();
        book = new OrderBook("TEST", events);
    }

    // ---------------------------------------------------------------- resting and spread

    @Test
    void nonCrossingOrdersRestOnBothSides() {
        book.submit(1, BUY, LIMIT, 99, 10);
        book.submit(2, SELL, LIMIT, 101, 5);

        assertEquals(99, book.bestBid());
        assertEquals(101, book.bestAsk());
        assertEquals(2, book.restingOrderCount());
        assertTrue(events.trades.isEmpty());
    }

    @Test
    void emptyBookReportsZeroPrices() {
        assertEquals(0, book.bestBid());
        assertEquals(0, book.bestAsk());
        assertTrue(book.depth(BUY, 5).isEmpty());
    }

    @Test
    void depthIsSortedBestFirstAndAggregated() {
        book.submit(1, BUY, LIMIT, 98, 10);
        book.submit(2, BUY, LIMIT, 100, 5);
        book.submit(3, BUY, LIMIT, 100, 7);
        book.submit(4, BUY, LIMIT, 99, 1);

        List<Level> bids = book.depth(BUY, 2);
        assertEquals(List.of(new Level(100, 12, 2), new Level(99, 1, 1)), bids);
        assertEquals(3, book.levelCount(BUY));
    }

    // ---------------------------------------------------------------- price time priority

    @Test
    void earlierOrderAtSamePriceFillsFirst() {
        book.submit(1, SELL, LIMIT, 100, 5);
        book.submit(2, SELL, LIMIT, 100, 5);
        book.submit(3, BUY, LIMIT, 100, 7);

        assertEquals(2, events.trades.size());
        assertEquals(new Trade(3, 1, BUY, 100, 5), events.trades.get(0));
        assertEquals(new Trade(3, 2, BUY, 100, 2), events.trades.get(1));
        assertNull(book.order(1));
        assertEquals(3, book.order(2).remaining());
    }

    @Test
    void betterPriceFillsBeforeEarlierTime() {
        book.submit(1, SELL, LIMIT, 101, 5);
        book.submit(2, SELL, LIMIT, 100, 5);
        book.submit(3, BUY, LIMIT, 101, 5);

        assertEquals(new Trade(3, 2, BUY, 100, 5), events.trades.get(0));
        assertEquals(101, book.bestAsk());
    }

    @Test
    void tradesExecuteAtMakerPrice() {
        book.submit(1, BUY, LIMIT, 105, 5);
        book.submit(2, SELL, LIMIT, 100, 5);

        assertEquals(105, events.trades.get(0).price());
    }

    @Test
    void aggressiveOrderSweepsLevelsThenRestsRemainder() {
        book.submit(1, SELL, LIMIT, 100, 3);
        book.submit(2, SELL, LIMIT, 101, 3);
        book.submit(3, SELL, LIMIT, 103, 3);
        long filled = book.submit(4, BUY, LIMIT, 102, 10);

        assertEquals(6, filled);
        assertEquals(102, book.bestBid());
        assertEquals(4, book.quantityAt(BUY, 102));
        assertEquals(103, book.bestAsk());
    }

    // ---------------------------------------------------------------- partial fills

    @Test
    void partialFillLeavesMakerAtFrontOfQueue() {
        book.submit(1, SELL, LIMIT, 100, 10);
        book.submit(2, SELL, LIMIT, 100, 10);
        book.submit(3, BUY, LIMIT, 100, 4);

        List<Order> queue = book.ordersAt(SELL, 100);
        assertEquals(1, queue.get(0).id());
        assertEquals(6, queue.get(0).remaining());
        assertEquals(16, book.quantityAt(SELL, 100));
    }

    // ---------------------------------------------------------------- order types

    @Test
    void marketOrderTakesBestPricesAndCancelsRemainder() {
        book.submit(1, SELL, LIMIT, 100, 3);
        book.submit(2, SELL, LIMIT, 150, 3);
        long filled = book.submit(3, BUY, MARKET, 0, 10);

        assertEquals(6, filled);
        assertEquals(0, book.bestAsk());
        assertEquals(0, book.bestBid());
        assertTrue(events.events.contains("CANCEL 3 4 " + UNFILLED_REMAINDER));
    }

    @Test
    void iocNeverRests() {
        book.submit(1, SELL, LIMIT, 100, 3);
        long filled = book.submit(2, BUY, IOC, 100, 10);

        assertEquals(3, filled);
        assertEquals(0, book.bestBid());
        assertNull(book.order(2));
        assertTrue(events.events.contains("CANCEL 2 7 " + UNFILLED_REMAINDER));
    }

    @Test
    void iocRespectsLimitPrice() {
        book.submit(1, SELL, LIMIT, 101, 3);
        assertEquals(0, book.submit(2, BUY, IOC, 100, 3));
        assertEquals(3, book.quantityAt(SELL, 101));
    }

    @Test
    void fokFillsCompletelyWhenLiquidityIsThere() {
        book.submit(1, SELL, LIMIT, 100, 3);
        book.submit(2, SELL, LIMIT, 101, 3);
        assertEquals(5, book.submit(3, BUY, FOK, 101, 5));
        assertEquals(1, book.quantityAt(SELL, 101));
    }

    @Test
    void fokKilledWithoutTouchingBookWhenLiquidityIsShort() {
        book.submit(1, SELL, LIMIT, 100, 3);
        book.submit(2, SELL, LIMIT, 102, 10);
        assertEquals(0, book.submit(3, BUY, FOK, 101, 5));

        assertTrue(events.trades.isEmpty());
        assertEquals(3, book.quantityAt(SELL, 100));
        assertTrue(events.events.contains("CANCEL 3 5 " + FILL_OR_KILL_FAILED));
    }

    @Test
    void postOnlyRestsWhenPassive() {
        book.submit(1, SELL, LIMIT, 101, 3);
        book.submit(2, BUY, POST_ONLY, 100, 3);
        assertEquals(100, book.bestBid());
    }

    @Test
    void postOnlyRejectedWhenItWouldTake() {
        book.submit(1, SELL, LIMIT, 100, 3);
        book.submit(2, BUY, POST_ONLY, 100, 3);

        assertTrue(events.trades.isEmpty());
        assertEquals(0, book.bestBid());
        assertTrue(events.events.contains("REJECT 2 POST_ONLY_WOULD_TAKE"));
    }

    // ---------------------------------------------------------------- cancel

    @Test
    void cancelRemovesOrderAndEmptyLevel() {
        book.submit(1, BUY, LIMIT, 100, 5);
        assertTrue(book.cancel(1));
        assertEquals(0, book.bestBid());
        assertEquals(0, book.levelCount(BUY));
    }

    @Test
    void cancelFromMiddleOfQueueKeepsOthersInOrder() {
        book.submit(1, BUY, LIMIT, 100, 1);
        book.submit(2, BUY, LIMIT, 100, 2);
        book.submit(3, BUY, LIMIT, 100, 3);
        book.cancel(2);

        List<Order> queue = book.ordersAt(BUY, 100);
        assertEquals(2, queue.size());
        assertEquals(1, queue.get(0).id());
        assertEquals(3, queue.get(1).id());
        assertEquals(4, book.quantityAt(BUY, 100));
    }

    @Test
    void cancelUnknownOrderIsRejected() {
        assertFalse(book.cancel(42));
        assertTrue(events.events.contains("REJECT 42 UNKNOWN_ORDER_ID"));
    }

    @Test
    void filledOrderCannotBeCancelled() {
        book.submit(1, SELL, LIMIT, 100, 5);
        book.submit(2, BUY, LIMIT, 100, 5);
        assertFalse(book.cancel(1));
    }

    // ---------------------------------------------------------------- modify

    @Test
    void reducingQuantityKeepsQueuePriority() {
        book.submit(1, BUY, LIMIT, 100, 10);
        book.submit(2, BUY, LIMIT, 100, 10);
        book.modify(1, 100, 4);

        assertEquals(1, book.ordersAt(BUY, 100).get(0).id());
        assertEquals(14, book.quantityAt(BUY, 100));
    }

    @Test
    void increasingQuantityLosesQueuePriority() {
        book.submit(1, BUY, LIMIT, 100, 10);
        book.submit(2, BUY, LIMIT, 100, 10);
        book.modify(1, 100, 20);

        List<Order> queue = book.ordersAt(BUY, 100);
        assertEquals(2, queue.get(0).id());
        assertEquals(1, queue.get(1).id());
        assertEquals(30, book.quantityAt(BUY, 100));
    }

    @Test
    void priceChangeMovesOrderToNewLevel() {
        book.submit(1, BUY, LIMIT, 100, 10);
        book.modify(1, 99, 10);

        assertEquals(99, book.bestBid());
        assertEquals(0, book.quantityAt(BUY, 100));
        assertEquals(1, book.levelCount(BUY));
    }

    @Test
    void modifyAcrossSpreadTradesImmediately() {
        book.submit(1, SELL, LIMIT, 101, 4);
        book.submit(2, BUY, LIMIT, 99, 10);
        book.modify(2, 101, 10);

        assertEquals(new Trade(2, 1, BUY, 101, 4), events.trades.get(0));
        assertEquals(6, book.quantityAt(BUY, 101));
        assertEquals(0, book.bestAsk());
    }

    @Test
    void modifyUnknownOrderIsRejected() {
        assertFalse(book.modify(7, 100, 1));
    }

    // ---------------------------------------------------------------- validation

    @Test
    void invalidOrdersAreRejected() {
        book.submit(1, BUY, LIMIT, 100, 0);
        book.submit(2, BUY, LIMIT, 0, 5);
        book.submit(3, BUY, LIMIT, 100, 5);
        book.submit(3, BUY, LIMIT, 100, 5);

        assertTrue(events.events.contains("REJECT 1 INVALID_QUANTITY"));
        assertTrue(events.events.contains("REJECT 2 INVALID_PRICE"));
        assertTrue(events.events.contains("REJECT 3 DUPLICATE_ORDER_ID"));
        assertEquals(5, book.quantityAt(BUY, 100));
    }

    @Test
    void tradeReportsBuyAndSellIds() {
        book.submit(1, BUY, LIMIT, 100, 5);
        book.submit(2, SELL, LIMIT, 100, 5);
        Trade t = events.trades.get(0);
        assertEquals(1, t.buyOrderId());
        assertEquals(2, t.sellOrderId());
    }

    @Test
    void engineRoutesBySymbol() {
        MatchingEngine engine = new MatchingEngine();
        engine.submit("AAA", 1, BUY, LIMIT, 100, 5);
        engine.submit("BBB", 2, SELL, LIMIT, 100, 5);

        assertEquals(100, engine.book("AAA").bestBid());
        assertEquals(0, engine.book("AAA").bestAsk());
        assertNotNull(engine.book("BBB").order(2));
    }
}

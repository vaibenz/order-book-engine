package io.github.vaibenz.orderbook;

/**
 * Supported order types.
 *
 * <ul>
 *   <li>{@link #LIMIT}: matches up to its price, any remainder rests on the book.</li>
 *   <li>{@link #MARKET}: matches at any price, any remainder is cancelled.</li>
 *   <li>{@link #IOC}: immediate or cancel. Matches up to its price, remainder is cancelled.</li>
 *   <li>{@link #FOK}: fill or kill. Fills completely up to its price or does nothing.</li>
 *   <li>{@link #POST_ONLY}: rests on the book, rejected if it would take liquidity.</li>
 * </ul>
 */
public enum OrderType {
    LIMIT,
    MARKET,
    IOC,
    FOK,
    POST_ONLY;

    boolean hasPrice() {
        return this != MARKET;
    }

    boolean canRest() {
        return this == LIMIT || this == POST_ONLY;
    }
}

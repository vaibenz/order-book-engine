# order-book-engine

A price time priority limit order book matching engine in Java, with five order types, O(1) cancels, and a differential test suite that checks every event against a reference implementation.

Matching engines sit at the centre of every exchange, and I wanted to build one properly: correct first, then fast. The part I trust most isn't the benchmark, it's the differential test that replays random order flow against a simple, obviously correct reference book.

## What's in it

**Order types**

| Type | Behaviour |
|---|---|
| `LIMIT` | Matches up to its price, remainder rests on the book |
| `MARKET` | Matches at any price, remainder is cancelled |
| `IOC` | Immediate or cancel: matches up to its price, remainder is cancelled |
| `FOK` | Fill or kill: fills completely up to its price, or does nothing at all |
| `POST_ONLY` | Rests on the book, rejected if it would take liquidity |

**Commands:** submit, cancel, and modify. Reducing quantity at the same price keeps queue priority. A price change or quantity increase loses priority, and the order may trade immediately if the new price crosses.

**Events:** accepted, trade, rested, cancelled (with reason), modified, and rejected (with reason), delivered synchronously to an `OrderBookListener` in the order they happen.

**Multi symbol routing:** `MatchingEngine` keeps one book per symbol.

## Design

```
OrderBook
 ├── bids: TreeMap<price, PriceLevel>   highest first
 ├── asks: TreeMap<price, PriceLevel>   lowest first
 └── ordersById: HashMap<id, Order>

PriceLevel  (FIFO queue at one price)
 head ⇄ order ⇄ order ⇄ order ⇄ tail    intrusive doubly linked list
 totalQuantity, orderCount
```

* Integer prices and quantities. Prices are in ticks and quantities in lots, so matching never touches floating point.
* O(log L) to add at a new price level, O(1) to join an existing level. L is the number of price levels on that side.
* O(1) cancel. Each order is a node in its level's linked list and is indexed by id, so cancelling unlinks it directly without scanning the queue.
* Trades execute at the resting (maker) price. Walking the opposite side stops at the first level that no longer crosses.
* FOK checks liquidity before touching the book. A failed fill or kill leaves the book exactly as it was.
* Single threaded core. Like most exchange matching engines, the book expects commands to be serialised onto one thread, which keeps matching deterministic and lock free.

## Testing

```bash
mvn test
```

* `OrderBookTest`: 27 scenario tests covering price time priority, partial fills, level sweeps, every order type, cancels from the middle of a queue, modify priority rules and input validation.
* `DifferentialTest`: runs 200 random command streams of 2,000 commands each through both the real book and `ReferenceOrderBook`, a deliberately naive implementation that linear scans a flat list. The streams mix every order type with invalid input, duplicate ids, and cancels and modifies on live, filled and unknown orders. After every single command, both books must have emitted an identical event log, and their final depth must match. A planted priority bug is caught within the first two seeds.

## Benchmark

```bash
mvn -q package -DskipTests
java -Xms1g -Xmx1g -jar target/order-book-engine-1.0.0.jar 2000000 42
```

The benchmark first generates a replayable flow of 2 million commands around a random walk mid price: 55% limit orders near the touch, 25% cancels of live orders, 10% modifies, 7% IOC and 3% market orders. Generation is done up front, so random number generation isn't timed. It then replays the stream on fresh books after warmup runs.

Sample result (OpenJDK 21, 2 vCPU cloud VM, seed 42):

```
Mix: 1,314,880 submits, 489,391 cancels, 195,729 modifies
Trades per run: 744,665 (volume 19,020,102 lots)
Throughput: median 7.93 M commands/s (min 7.57, max 9.05) over 5 runs
Latency per command: p50 125 ns, p99 438 ns, p99.9 729 ns
```

The maximum single command latency is dominated by occasional GC pauses. These would be the next thing to remove, by pooling `Order` and `Trade` objects.

## What I'd add next

* Object pooling and primitive collections to cut allocation and GC pauses
* Self trade prevention
* Stop and iceberg orders
* A binary protocol gateway feeding the book through a ring buffer
* JMH microbenchmarks per operation

---

If something here is unclear, broken, or just wrong, feel free to open an issue. I'm always happy to chat about it.

# load-testing

A load-testing harness for Bosk drivers. This module is not published; it is a development tool.

The harness runs a conflict-free workload against a driver. Each worker owns a disjoint slice of the root catalog and repeatedly replaces the `string` field of its entities, so the final state is deterministic and can be verified exactly. It measures update and flush latency with per-worker HdrHistograms, samples heap and GC once per second, and writes the results to CSV.

Supported drivers:

- `simple`: the in-memory driver.
- `mongo-sequoia`: MongoDriver using SEQUOIA.
- `mongo-pando`: MongoDriver using Pando with the whole state in one document.
- `mongo-pando-grafted`: MongoDriver using Pando with `/catalog` and `/sideTable` grafted into separate documents.

The Mongo drivers start a disposable MongoDB replica set with Testcontainers, so Docker must be running. Each run gets a fresh container and a fresh collection.

## Running

Run one configuration per process so that JIT state and heap do not carry over between levels of a ramp.

```bash
./gradlew :load-testing:run --args="--threads=1 --warmup=10s --duration=30s --label=one"
./gradlew :load-testing:run --args="--threads=P/2 --warmup=10s --duration=30s --label=half"
./gradlew :load-testing:run --args="--threads=P --warmup=10s --duration=30s --label=all"
./gradlew :load-testing:run --args="--threads=2P --warmup=10s --duration=30s --label=twice"
./gradlew :load-testing:run --args="--threads=4P --warmup=10s --duration=30s --label=four"
./gradlew :load-testing:run --args="--driver=mongo-sequoia --threads=6 --warmup=3s --duration=10s --label=seq"
```

`P` is `Runtime.availableProcessors()`. Thread counts may also be absolute integers, `P/2`, `2P`, or `2*P`.

Options:

| Option | Default | Meaning |
|---|---|---|
| `--driver` | `simple` | `simple`, `mongo-sequoia`, `mongo-pando`, or `mongo-pando-grafted` |
| `--entities` | `10000` | Number of entities in the root catalog |
| `--threads` | `P` | Worker threads |
| `--warmup` | `10s` | Warmup duration; supports `ms`, `s`, `m`, `h` |
| `--duration` | `30s` | Measurement duration |
| `--flush` | `everyOp` | `everyOp`, `everyN`, or `end` |
| `--flushEvery` | `100` | Flush interval for `everyN` |
| `--valueBytes` | `16` | Length of the string values written by the workload |
| `--stallTimeout` | `60s` | How long to wait for workers to stop before dumping threads |
| `--label` | empty | Label included in the output |
| `--out` | `build/load-results` | Output directory |

The output directory contains:

- `summary.csv`: one row per run, with configuration, throughput, latency percentiles, heap, GC, and environment.
- `samples.csv`: one row per second per run, with elapsed time, operation count, heap, and GC.

Both files are appended to, so a ramp produces one analysis-ready file.

## Measurement notes

- Latency is recorded with HdrHistogram at 3 significant digits, so percentiles have about 0.1% relative error.
- Submit and flush latency are recorded separately.
- The workload is closed-loop. When the system slows, workers wait, so offered load falls. Closed-loop numbers are useful for finding sustainable concurrency; an open-loop mode is still to come.
- Warmup is excluded from the metrics.
- `heap_used_before` and `heap_used_after` in the summary are point-in-time values. The time series in `samples.csv` is the better source for heap and GC behaviour.
- The `max` latency is often a GC or scheduling spike. Look at the percentiles for the shape of the distribution.

## First baseline

Apple Silicon, 12 logical cores (6 performance, 6 efficiency), 9 GB max heap, JDK 27. `simpleDriver`, 10,000 entities, 16-byte values, 5 s warmup, 15 s measurement, flush after every update.

| Threads | Throughput (ops/s) | Submit p50 | Submit p99 | Submit max | GC collections |
|---:|---:|---:|---:|---:|---:|
| 1 | 1,970,068 | 375 ns | 1.042 us | 2.3 ms | 80 |
| 6 | 2,248,866 | 709 ns | 1.541 us | 30.4 ms | 89 |
| 12 | 2,369,657 | 708 ns | 1.333 us | 66.3 ms | 100 |
| 24 | 2,243,474 | 708 ns | 1.667 us | 125.8 ms | 87 |
| 48 | 2,406,233 | 708 ns | 1.167 us | 257.2 ms | 195 |

The in-memory driver plateaus at roughly 2.4 million operations per second on this machine. That plateau is the synchronized `LocalDriver`: it applies one update at a time, so extra threads add contention rather than parallel work. The per-update critical section is about 420 ns, which matches the single-thread submit mean of 427 ns. Adding threads beyond the performance-core count does not improve throughput; it raises mean and tail latency instead. The p99 stays around 1.3 to 1.7 microseconds while the maximum grows into the hundreds of milliseconds, which is consistent with scheduling and GC spikes rather than systemic queueing.

This is the baseline the Mongo runs will be compared against.

## State and value size

At 6 threads, keeping everything else the same:

| Entities | Value bytes | Throughput (ops/s) | Submit p50 | Submit p99 | Heap after | GC collections |
|---:|---:|---:|---:|---:|---:|---:|
| 10,000 | 16 | 2,248,866 | 709 ns | 1.541 us | 17.2 MB | 89 |
| 100,000 | 16 | 1,568,365 | 1.083 us | 2.209 us | 1,211.8 MB | 24 |
| 10,000 | 1024 | 1,897,315 | 875 ns | 1.875 us | 535.1 MB | 77 |

A larger catalog lowers throughput and raises per-update latency, even without serialization in the driver. Larger values cost allocation and GC; the 100,000-entity run had fewer collections but each one was longer, for 263 ms of total GC time against 87 ms for the 10,000-entity run. The `heap_used_after` column is a point-in-time value; use `samples.csv` for the sawtooth.

## Mongo results

10,000 entities, 16-byte values, 3 s warmup, 10 s measurement, flush after every update. Each run used a fresh MongoDB 9.0.4 replica set.

### SEQUOIA

| Threads | Ops/s | Submit p50 | Submit p99 | Flush p50 | Errors |
|---:|---:|---:|---:|---:|---:|
| 1 | 317 | 1.421 ms | 2.652 ms | 1.234 ms | 0 |
| 6 | 697 | 2.410 ms | 71.238 ms | 1.450 ms | 0 |
| 12 | 662 | 3.086 ms | 390.857 ms | 1.846 ms | 0 |
| 24 | 605 | 6.992 ms | 622.854 ms | 3.572 ms | 0 |

SEQUOIA sustains roughly 600 to 700 operations per second and stays correct through 24 threads. The flush p50 stays in the low milliseconds, but the submit p99 grows from about 2.7 ms to 623 ms as concurrency rises. That points at queueing in the driver's local apply path rather than at the database round trip.

### Pando one-big-document

| Threads | Ops/s | Submit p50 | Submit p99 | Errors |
|---:|---:|---:|---:|---:|
| 1 | 28 | 26.903 ms | 53.674 ms | 0 |
| 2 | 5 | 63.144 ms | 10.217 s | 0 |
| 4 | 17 | 128.647 ms | 204.210 ms | 84 |
| 6 | 2 | 37.585 ms | 10.251 s | 43 |

One-big-document is limited by rewriting the whole state document. At two threads it already spends 10 s tails in retries; at four and six threads it surfaces errors. A 1,000-entity run at six threads produced no successful updates and one error.

### Grafted Pando

Using `/catalog` and `/sideTable` as graft points:

| Threads | Entities | Ops/s | Errors |
|---:|---:|---:|---:|
| 1 | 10,000 | 912 | 0 |
| 2 | 10,000 | 0 | 0 |
| 4 | 10,000 | 0 | 1 |
| 6 | 10,000 | 8 | 45 |
| 12 | 10,000 | 1 | 62 |
| 6 | 1,000 | 1 | 9 |

Grafting helps a lot at one thread, where it reaches 912 ops/s, faster than SEQUOIA. Each update writes a small entity document instead of the whole state. It does not help under concurrency. Every Pando failure is the same:

```
DisconnectedException: MongoCommandException: error 112 (WriteConflict):
Write conflict during plan execution and yielding is disabled.
Please retry your operation or multi-document transaction.
```

The response carries the `TransientTransactionError` label. `MainDriver` retries a transient transaction error twice immediately, then disconnects and waits; under sustained contention it gives up and surfaces `DisconnectedException`. The 10 s tails correspond to that retry and disconnect cycle.

### Document size at one thread

The same MongoDB instance, one thread, 3 s warmup, 10 s measurement, flush after every update:

| Entities | Pando one-big-document | SEQUOIA | Grafted Pando |
|---:|---:|---:|---:|
| 100 | 1,182 | 1,588 | - |
| 1,000 | 435 | 1,098 | 1,002 |
| 10,000 | 28 | 260 | 819 |

This is the document-size curve, not a MongoDB concurrency ceiling. Pando one-big-document goes from 1,182 ops/s at 100 entities to 28 at 10,000, because each update rewrites the whole state document twice inside a transaction. SEQUOIA also slows down with state size, but it performs one atomic update. Grafted Pando stays around 1,000 ops/s because it writes small entity documents.

At 100 entities, even the Pando transaction path exceeds 1,000 ops/s. The 28 ops/s figure is therefore a property of the one-big-document format at 10,000 entities, not of MongoDB's general write throughput.

### Why Pando behaves differently

Two things differ from SEQUOIA:

1. **Pando uses a multi-document transaction for every update.** `PandoFormatDriver.doReplacement` and `doDelete` call `ensureTransactionStarted`, and `MainDriver` commits the transaction after the operation. `SequoiaFormatDriver.doUpdate` is a single atomic `updateOne` with no transaction.
2. **Pando bumps the `revision` field of the root document on every update.** For a grafted entity, the entity document is separate, but `doReplacement` still calls `doUpdate(blankUpdateDoc(), documentFilter(rootRef))` to increment the collection-wide revision. `multi-doc.md` describes that revision as the collection-wide flush marker. Every update therefore writes the root document.

Concurrent transactions that write the same document conflict. Our workload is deliberately disjoint at the entity level, so in the grafted case the root revision is the only shared write. That makes the conflict easy to attribute. Graft points solve the 16 MB document limit, not the write-concurrency limit.

The single-thread difference between one-big-document and SEQUOIA comes from write amplification. The one-big-document branch pre-deletes the field and then sets it in the same large root document, inside a transaction, so each update rewrites the state document twice. SEQUOIA performs one atomic update that sets the field and increments the revision. Grafted Pando writes a small entity document plus the small root revision document, which is why it is fastest at one thread.

Flush is not the main factor. Turning it off moved one-big-document from 28 to 36 ops/s and SEQUOIA from 317 to 456 ops/s.

### Possible directions

- Give the grafted documents their own revisions, or separate the flush/ordering marker, so a grafted update does not write the root document. This is the only way to make Pando scale for concurrent writers.
- Avoid the pre-delete for a simple field replacement, so one-big-document performs one write instead of two.
- Replace the two immediate retries plus disconnect with backoff and jitter. That turns a conflict into latency instead of a visible error, but it does not remove the root-document bottleneck.
- Batch several updates into one transaction and one revision bump.
- Until then, treat grafted Pando as a single-writer format: 912 ops/s at one thread, with multiple readers.

One reporting caveat: `verified=true` means the final state matched the last successfully submitted values. When updates fail, the workload does not record them, so the final state can still match. The `errors` column and the process exit code are the failure signal.

The next steps are to test the Pando hypotheses in `bosk-mongo`, run SEQUOIA at larger state sizes and under faults, and add a read-only workload.

# Failure Semantics for Concurrent Microservice Calls

This project implements three failure-handling policies for concurrent microservice calls using `CompletableFuture`.

## Context
Given a list of microservices and a corresponding list of messages (same size), each microservice is called asynchronously using `retrieveAsync(message)`.

The policies define how the overall computation behaves when one or more microservices fail.

---

## 1) Fail-Fast (Atomic)

**Method:** `processAsyncFailFast(services, messages)`

**Rule:** If *any* microservice call fails, the entire operation fails.

**Result:**
- The returned `CompletableFuture` completes **exceptionally** if at least one call fails.
- No partial result is returned.

**Why/When to use:**
- When partial results are not meaningful (e.g., must have all values to proceed).
- When you want immediate feedback and clear failure propagation.

**Trade-off:**
- One failure cancels the usefulness of all other successful calls (even if they completed).

---

## 2) Fail-Partial (Best-effort)

**Method:** `processAsyncFailPartial(services, messages)`

**Rule:** Failures are handled per microservice. The overall operation still succeeds.

**Result:**
- The returned `CompletableFuture` completes **normally**.
- The output list contains **only successful** results.
- Failed calls are **skipped** (not included).

**Why/When to use:**
- When partial results are still valuable (e.g., aggregating optional data from multiple sources).
- When you want resilience but still want to observe missing data.

**Trade-off:**
- Callers must be prepared to handle missing entries (smaller result list).

---

## 3) Fail-Soft (Fallback)

**Method:** `processAsyncFailSoft(services, messages, fallbackValue)`

**Rule:** Any failing microservice result is replaced with a fallback value.

**Result:**
- The returned `CompletableFuture` completes **normally**.
- The output always has the same “shape” as the input (a value per service).
- Failures become the provided `fallbackValue`.

**Why/When to use:**
- When you must return something to keep the system responsive (e.g., UI display).
- When a safe default exists.

**Risk / Important note:**
- This policy can **mask real failures** (everything looks “successful” to callers).
- It should be used only when fallback is truly acceptable, and failures are still logged/monitored elsewhere.

---

## Notes on Concurrency & Ordering

- Calls are started concurrently via `CompletableFuture`.
- Completion order is nondeterministic due to scheduling and varying delays.
- Tests must not assume a fixed completion order unless explicitly designed.

- Minor clarification added during review.

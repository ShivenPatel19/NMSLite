package com.nmslite.core;

import io.vertx.core.Future;

import io.vertx.core.Handler;
import io.vertx.core.WorkerExecutor;

import io.vertx.core.json.JsonArray;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.ArrayList;

import java.util.List;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Parallel batch processor with backpressure handling using Vert.x WorkerExecutor.

 * This class provides efficient parallel processing of large datasets in configurable
 * batch sizes using a dedicated worker thread pool. It automatically handles backpressure
 * by queuing batches when all threads are busy.

 * Features:
 * - Parallel processing: Multiple batches processed concurrently
 * - Backpressure: Automatic queue management via WorkerExecutor
 * - Thread pool: Uses dedicated WorkerExecutor for blocking operations
 * - Efficient: Threads stay busy until all batches are processed
 * - Fail-tolerant: Continues processing remaining batches on failure
 * - Generic: Reusable across different batch processing scenarios

 * Architecture:
 * - Items split into batches upfront
 * - All batches submitted to WorkerExecutor
 * - WorkerExecutor manages thread pool + internal queue
 * - As threads finish, they pick next batch from queue
 * - Results collected and returned when all batches complete

 * Example: 1000 devices, batch size 50, 10 threads
 * - Creates 20 batches (1000 / 50)
 * - First 10 batches start immediately (one per thread)
 * - Remaining 10 batches wait in WorkerExecutor queue
 * - As thread finishes, it picks next batch from queue
 * - All 10 threads stay busy until all 20 batches done

 * Usage Pattern:
 * 1. Extend this class and implement processBatch() method
 * 2. Optionally override handleBatchFailure() for custom error handling
 * 3. Create instance with items, batch size, and worker executor
 * 4. Call processAllBatches() to collect results OR processAllBatchesFireAndForget() for side effect only processing
 * 5. Future completes when all batches processed (processAllBatches) or immediately after submission (fire-and-forget)

 * Example:
 * <pre>
 * class MyBatchProcessor extends ParallelBatchProcessor&lt;String&gt; {
 *     public MyBatchProcessor(List&lt;String&gt; items, int batchSize, WorkerExecutor executor) {
 *         super(items, batchSize, executor);
 *     }
 *
 *     protected JsonArray processBatch(List&lt;String&gt; batch) {
 *         // Process batch and return results (BLOCKING operation)
 *         var results = new JsonArray();
 *         for (String item : batch) {
 *             // Process item (blocking operations allowed here)
 *             results.add(processItem(item));
 *         }
 *         return results;
 *     }
 *
 *     protected void handleBatchFailure(List&lt;String&gt; batch, Throwable cause) {
 *         // Handle batch failure (optional)
 *     }
 * }
 *
 * // For result collection (e.g., DiscoveryVerticle):
 * MyBatchProcessor processor = new MyBatchProcessor(items, 50, workerExecutor);
 * return processor.processAllBatches();
 *
 * // For fire-and-forget (e.g., AvailabilityVerticle):
 * MyBatchProcessor processor = new MyBatchProcessor(items, 50, workerExecutor);
 * return processor.processAllBatchesFireAndForget();
 * </pre>
 *
 * @param <T> Type of items to process in batches
 */
public abstract class ParallelBatchProcessor<T>
{

    private static final Logger logger = LoggerFactory.getLogger(ParallelBatchProcessor.class);

    private final List<T> items;

    private final int batchSize;

    private final WorkerExecutor workerExecutor;

    private final AtomicInteger processedBatches;

    private final AtomicInteger failedBatches;

    /**
     * Constructs a new ParallelBatchProcessor.
     *
     * @param items List of items to process in batches
     * @param batchSize Maximum number of items per batch
     * @param workerExecutor WorkerExecutor for parallel batch processing
     */
    public ParallelBatchProcessor(List<T> items, int batchSize, WorkerExecutor workerExecutor)
    {
        this.items = items;

        this.batchSize = batchSize;

        this.workerExecutor = workerExecutor;

        this.processedBatches = new AtomicInteger(0);

        this.failedBatches = new AtomicInteger(0);
    }

    /**
     * Process all batches in parallel using WorkerExecutor.

     * This method:
     * 1. Splits items into batches
     * 2. Submits all batches to WorkerExecutor (parallel execution)
     * 3. WorkerExecutor handles backpressure automatically
     * 4. Collects results from all batches
     * 5. Returns Future with all results

     * Backpressure handling:
     * - WorkerExecutor has internal queue
     * - When batches > threads: excess batches wait in queue
     * - As thread finishes, it picks next batch from queue
     * - All threads stay busy until all batches processed

     * @return Future containing JsonArray of all results
     */
    public Future<JsonArray> processAllBatches()
    {
        try
        {
            if (items.isEmpty())
            {
                logger.info("No items to process");

                return Future.succeededFuture(new JsonArray());
            }

            // Split items into batches upfront
            var batches = createBatches();

            logger.info("Starting parallel batch processing: {} items in {} batches (batch size: {})",
                    items.size(), batches.size(), batchSize);

            // Submit all batches to WorkerExecutor (parallel execution with backpressure)
            var batchFutures = new ArrayList<Future<JsonArray>>();

            for (int i = 0; i < batches.size(); i++)
            {
                var batch = batches.get(i);

                var batchIndex = i + 1;

                var batchFuture = processBatchAsync(batch, batchIndex, batches.size());

                batchFutures.add(batchFuture);
            }

            // Wait for all batches to complete and collect results
            return Future.all(batchFutures)
                    .map(compositeFuture ->
                    {
                        var allResults = new JsonArray();

                        for (int i = 0; i < compositeFuture.size(); i++)
                        {
                            var batchResults = compositeFuture.<JsonArray>resultAt(i);

                            if (batchResults != null)
                            {
                                for (var result : batchResults)
                                {
                                    allResults.add(result);
                                }
                            }
                        }

                        logger.info("Parallel batch processing completed: {} batches processed, {} failed, {} total results",
                                processedBatches.get(), failedBatches.get(), allResults.size());

                        return allResults;
                    })
                    .recover(throwable ->
                    {
                        logger.error("Error in Future.all composition: {}", throwable.getMessage());

                        // Return empty results on failure (fail-tolerant)
                        return Future.succeededFuture(new JsonArray());
                    });
        }
        catch (Exception exception)
        {
            logger.error("Error in processAllBatches: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Process all batches in parallel without waiting for completion (fire-and-forget pattern).
     
     * This method is optimized for side effect driven processing where:
     * - No results need to be collected
     * - Each batch updates cache/database independently
     * - No post-processing or aggregation needed
     * - Real-time updates are preferred over waiting for all batches
     
     * Use cases:
     * - AvailabilityVerticle: Updates cache/DB as each batch completes
     * - Any periodic background task that doesn't need result collection
     
     * Differences from processAllBatches():
     * - Returns immediately after submitting all batches (doesn't wait)
     * - No result collection or aggregation
     * - No Future.all() waiting
     * - Batches execute in background
     * - Faster return, better for real-time updates
     
     * Architecture:
     * 1. Split items into batches
     * 2. Submit all batches to WorkerExecutor
     * 3. Return immediately (don't track futures)
     * 4. Each batch processes independently in background
     * 5. Failures handled via handleBatchFailure()
     
     * Example:
     * Batch 1 completes at T+2s → updates cache immediately
     * Batch 2 completes at T+3s → updates cache immediately
     * Batch 3 completes at T+8s → updates cache immediately
     * (vs Future.all: all update at T+8s)
     *
     * @return Future that completes immediately after submitting all batches
     */
    public Future<Void> processAllBatchesFireAndForget()
    {
        try
        {
            if (items.isEmpty())
            {
                logger.info("No items to process (fire-and-forget)");

                return Future.succeededFuture();
            }

            // Split items into batches upfront
            var batches = createBatches();

            logger.info("Starting fire-and-forget batch processing: {} items in {} batches (batch size: {})",
                    items.size(), batches.size(), batchSize);

            // Submit all batches without tracking futures (fire-and-forget)
            for (int i = 0; i < batches.size(); i++)
            {
                var batch = batches.get(i);

                var batchIndex = i + 1;

                // Submit batch and attach failure handler only (don't store future)
                processBatchAsync(batch, batchIndex, batches.size())
                        .onFailure(cause ->
                        {
                            logger.error("Fire-and-forget batch {}/{} failed: {}", batchIndex, batches.size(), cause.getMessage());

                            // handleBatchFailure already called in processBatchAsync, this is just for logging
                        });
            }

            logger.info("Fire-and-forget batch processing: all {} batches submitted to WorkerExecutor", batches.size());

            // Return immediately (don't wait for batches to complete)
            return Future.succeededFuture();
        }
        catch (Exception exception)
        {
            logger.error("Error in processAllBatchesFireAndForget: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Process all batches with completion callback (fire-and-forget with completion tracking).

     * This method combines the benefits of fire-and-forget with completion tracking:
     * - Each batch processes its results IMMEDIATELY when it completes (no waiting for other batches)
     * - Completion handler called when ALL batches finish (for post-processing like Phase 2)
     * - Returns immediately after submitting all batches (non-blocking)

     * How it works:
     * 1. Submit all batches to WorkerExecutor
     * 2. Attach individual onSuccess/onFailure handlers to each batch future
     * 3. Each handler runs IMMEDIATELY when that batch completes (independent of other batches)
     * 4. Track completion count with AtomicInteger
     * 5. When last batch completes, call completion handler
     * 6. Return immediately (don't wait for batches)

     * Use case: PollingMetricsVerticle
     * - Store metrics as each batch completes (don't wait for all batches)
     * - Trigger Phase 2 (auto-disable) when all batches done

     * Timeline example (5 batches):
     * T+0s:  Submit all 5 batches → Return immediately
     * T+5s:  Batch 1 completes → Process result NOW (completedBatches = 1/5)
     * T+7s:  Batch 2 completes → Process result NOW (completedBatches = 2/5)
     * T+10s: Batch 3 completes → Process result NOW (completedBatches = 3/5)
     * T+12s: Batch 4 completes → Process result NOW (completedBatches = 4/5)
     * T+15s: Batch 5 completes → Process result NOW (completedBatches = 5/5) → Call completion handler
     *
     * @param completionHandler Handler called when ALL batches complete (can be null)
     * @return Future that completes immediately after submitting all batches
     */
    public Future<Void> processAllBatchesWithCompletion(Handler<Void> completionHandler)
    {
        try
        {
            if (items.isEmpty())
            {
                logger.info("No items to process (with completion tracking)");

                // No batches, call completion handler immediately
                if (completionHandler != null)
                {
                    completionHandler.handle(null);
                }

                return Future.succeededFuture();
            }

            // Split items into batches upfront
            var batches = createBatches();

            var totalBatches = batches.size();

            // Track completion count (thread-safe)
            var completedBatches = new AtomicInteger(0);

            logger.info("Starting batch processing with completion tracking: {} items in {} batches (batch size: {})",
                    items.size(), batches.size(), batchSize);

            // Submit all batches and attach individual handlers (fire-and-forget style)
            for (int i = 0; i < batches.size(); i++)
            {
                var batch = batches.get(i);

                var batchIndex = i + 1;

                // Submit batch and attach handler (NON-BLOCKING - returns immediately)
                processBatchAsync(batch, batchIndex, totalBatches)
                        .onSuccess(batchResult ->
                        {
                            // ✅ This runs IMMEDIATELY when THIS batch completes
                            // ✅ Doesn't wait for other batches
                            // ✅ Runs on event loop (non-blocking)

                            logger.debug("Batch {}/{} completed and processed immediately", batchIndex, totalBatches);

                            // Track completion
                            var completed = completedBatches.incrementAndGet();

                            logger.debug("Completion progress: {}/{} batches done", completed, totalBatches);

                            // Check if ALL batches done
                            if (completed == totalBatches)
                            {
                                logger.info("All {} batches completed, calling completion handler", totalBatches);

                                // Call completion handler (e.g., trigger Phase 2)
                                if (completionHandler != null)
                                {
                                    completionHandler.handle(null);
                                }
                            }
                        })
                        .onFailure(cause ->
                        {
                            // Handle failure independently
                            logger.error("Batch {}/{} failed: {}", batchIndex, totalBatches, cause.getMessage());

                            handleBatchFailure(batch, cause);

                            // Still track completion (even on failure)
                            var completed = completedBatches.incrementAndGet();

                            logger.debug("Completion progress (with failure): {}/{} batches done", completed, totalBatches);

                            // Check if ALL batches done
                            if (completed == totalBatches)
                            {
                                logger.info("All {} batches completed (some failed), calling completion handler", totalBatches);

                                // Call completion handler even if some batches failed
                                if (completionHandler != null)
                                {
                                    completionHandler.handle(null);
                                }
                            }
                        });

                // ❌ DON'T store the future - fire and forget!
                // Handler attached above will run when batch completes
            }

            logger.info("All {} batches submitted to WorkerExecutor, returning immediately", batches.size());

            // Return immediately (don't wait for batches to complete)
            return Future.succeededFuture();
        }
        catch (Exception exception)
        {
            logger.error("Error in processAllBatchesWithCompletion: {}", exception.getMessage());

            return Future.failedFuture(exception);
        }
    }

    /**
     * Process a single batch asynchronously using WorkerExecutor.

     * Submits batch to WorkerExecutor for parallel processing.
     * WorkerExecutor handles thread pool management and backpressure.

     * @param batch List of items in this batch
     * @param batchIndex Current batch number (1-based)
     * @param totalBatches Total number of batches
     * @return Future containing results from this batch
     */
    private Future<JsonArray> processBatchAsync(List<T> batch, int batchIndex, int totalBatches)
    {
        try
        {
            return workerExecutor.executeBlocking(() ->
                    {
                        try
                        {
                            logger.debug("Processing batch {}/{} ({} items)", batchIndex, totalBatches, batch.size());

                            // Call subclass implementation (blocking operation allowed in worker thread)
                            var results = processBatch(batch);

                            processedBatches.incrementAndGet();

                            logger.debug("Batch {}/{} completed successfully ({} results)",
                                    batchIndex, totalBatches, results != null ? results.size() : 0);

                            return results;
                        }
                        catch (Exception exception)
                        {
                            failedBatches.incrementAndGet();

                            logger.error("Error processing batch {}/{}: {}", batchIndex, totalBatches, exception.getMessage());

                            handleBatchFailure(batch, exception);

                            // Return empty results for failed batch (fail-tolerant)
                            return new JsonArray();
                        }
                    },
                    false  // ordered = false (allows parallel execution)
            )
            .onSuccess(results ->
                    logger.debug("Batch {}/{} Future succeeded with {} results", batchIndex, totalBatches, results != null ? results.size() : 0))
            .onFailure(throwable ->
            {
                failedBatches.incrementAndGet();

                logger.error("Batch {}/{} Future failed: {}", batchIndex, totalBatches, throwable.getMessage());

                handleBatchFailure(batch, throwable);
            })
            .recover(throwable ->
            {
                logger.debug("Recovering from batch {}/{} failure", batchIndex, totalBatches);

                // Return empty results for failed batch (fail-tolerant)
                return Future.succeededFuture(new JsonArray());
            });
        }
        catch (Exception exception)
        {
            logger.error("Error in processBatchAsync: {}", exception.getMessage());

            return Future.succeededFuture(new JsonArray());
        }
    }

    /**
     * Split items into batches.

     * Creates list of batches where each batch contains up to batchSize items.
     * Last batch may contain fewer items if total items not evenly divisible.

     * @return List of batches
     */
    private List<List<T>> createBatches()
    {
        try
        {
            var batches = new ArrayList<List<T>>();

            for (int i = 0; i < items.size(); i += batchSize)
            {
                var endIndex = Math.min(i + batchSize, items.size());

                var batch = items.subList(i, endIndex);

                batches.add(new ArrayList<>(batch));  // Create copy to avoid concurrent modification
            }

            return batches;
        }
        catch (Exception exception)
        {
            logger.error("Error in createBatches: {}", exception.getMessage());

            return new ArrayList<>();
        }
    }

    /**
     * Process a single batch of items (BLOCKING operation).

     * This abstract method must be implemented by subclasses to define
     * the actual batch processing logic. The method should process all
     * items in the batch and return the results directly.

     * The implementation should:
     * - Process all items in the batch
     * - Return results as JsonArray
     * - Handle item-level errors appropriately (use try-catch)
     * - Can use blocking operations (runs in WorkerExecutor thread)

     * Note: This method is called from WorkerExecutor thread, so blocking
     * operations are ALLOWED and EXPECTED.

     * @param batch List of items to process in this batch
     * @return JsonArray of results from processing this batch
     */
    protected abstract JsonArray processBatch(List<T> batch);

    /**
     * Handle batch processing failure.

     * This method is called when processBatch() fails. Subclasses can override
     * this method to implement custom error handling logic such as:
     * - Tracking failed items
     * - Logging detailed error information
     * - Updating failure counters
     * - Notifying external systems

     * Default implementation: logs error only (already logged by processBatchAsync)

     * Note: After this method returns, processing continues with remaining batches
     * (fail-tolerant behavior). The failed batch is not retried automatically.

     * @param batch The batch that failed to process
     * @param cause The exception that caused the failure
     */
    protected abstract void handleBatchFailure(List<T> batch, Throwable cause);

}


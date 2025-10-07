package com.nmslite.core;

import io.vertx.core.Future;

import io.vertx.core.Promise;

import io.vertx.core.json.JsonArray;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.ArrayList;

import java.util.List;

import java.util.Queue;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Generic queue-based batch processor for sequential batch processing.
 *
 * This class provides a memory-efficient, fail-tolerant approach to processing
 * large datasets in configurable batch sizes. It uses a queue-based pull model
 * where batches are created on-demand rather than pre-partitioning all data.
 *
 * Features:
 * - Memory efficient: Only current batch held in memory at any time
 * - Fail-tolerant: Continues processing remaining batches on failure
 * - Sequential processing: Batches processed one after another
 * - Recursive pattern: Functional programming style with tail recursion
 * - Generic: Reusable across different batch processing scenarios
 * - Thread-safe: Uses ConcurrentLinkedQueue for queue operations
 *
 * Usage Pattern:
 * 1. Extend this class and implement processBatch() method
 * 2. Optionally override handleBatchFailure() for custom error handling
 * 3. Create instance with items to process and batch size
 * 4. Call processNext() to start batch processing
 * 5. Promise completes when all batches processed or queue empty
 *
 * Example:
 * <pre>
 * class MyBatchProcessor extends QueueBatchProcessor&lt;String&gt; {
 *     protected Future&lt;JsonArray&gt; processBatch(List&lt;String&gt; batch) {
 *         // Process batch and return results
 *     }
 * }
 *
 * MyBatchProcessor processor = new MyBatchProcessor(items, 50);
 * Promise&lt;JsonArray&gt; promise = Promise.promise();
 * processor.processNext(promise);
 * return promise.future();
 * </pre>
 *
 * @param <T> Type of items to process in batches
 */
public abstract class QueueBatchProcessor<T>
{
    private static final Logger logger = LoggerFactory.getLogger(QueueBatchProcessor.class);

    private final Queue<T> remainingItems;

    private final JsonArray allResults;

    private final int batchSize;

    private int processedBatches;

    private final int totalItems;

    /**
     * Constructs a new QueueBatchProcessor with the given items and batch size.
     *
     * Initializes the internal queue with all items and prepares for batch processing.
     * Items are added to a thread-safe ConcurrentLinkedQueue for sequential polling.
     *
     * @param items List of items to process in batches
     * @param batchSize Maximum number of items per batch
     */
    public QueueBatchProcessor(List<T> items, int batchSize)
    {
        this.remainingItems = new ConcurrentLinkedQueue<>(items);

        this.allResults = new JsonArray();

        this.batchSize = batchSize;

        this.processedBatches = 0;

        this.totalItems = items.size();

        logger.debug("📋 Queue initialized: {} items, batch size: {}", totalItems, batchSize);
    }

    /**
     * Process the next batch from the queue recursively.
     *
     * This method implements the core batch processing loop using tail recursion.
     * It polls items from the queue, processes them as a batch, and recursively
     * calls itself to process the next batch until the queue is empty.
     *
     * Processing flow:
     * 1. Check if queue is empty → complete promise if done
     * 2. Poll next batch of items from queue
     * 3. Call processBatch() (implemented by subclass)
     * 4. On success: accumulate results and recurse
     * 5. On failure: handle error and recurse (fail-tolerant)
     *
     * The recursion continues until all items are processed or queue is empty.
     * Each batch is processed completely before moving to the next batch.
     *
     * @param promise Promise to complete with all accumulated results when processing finishes
     */
    public void processNext(Promise<JsonArray> promise)
    {
        if (remainingItems.isEmpty())
        {
            logger.info("🎉 All batches completed: {} results from {} items",

                allResults.size(), totalItems);

            promise.complete(allResults);

            return;
        }

        List<T> currentBatch = pollBatchFromQueue();

        if (currentBatch.isEmpty())
        {
            logger.info("🎉 Queue empty, completing with {} results", allResults.size());

            promise.complete(allResults);

            return;
        }

        processedBatches++;

        int remainingCount = remainingItems.size();

        logger.info("🔄 Processing batch {} ({} items, {} remaining in queue)",

            processedBatches, currentBatch.size(), remainingCount);

        processBatch(currentBatch)
            .onSuccess(batchResults ->
            {
                if (batchResults != null)
                {
                    for (Object result : batchResults)
                    {
                        allResults.add(result);
                    }
                }

                logger.info("✅ Batch {} completed: {} results, {} total results, {} items remaining",

                    processedBatches, batchResults != null ? batchResults.size() : 0,

                    allResults.size(), remainingItems.size());

                currentBatch.clear();

                processNext(promise);
            })
            .onFailure(cause ->
            {
                logger.error("❌ Batch {} failed: {}", processedBatches, cause.getMessage());

                handleBatchFailure(currentBatch, cause);

                currentBatch.clear();

                processNext(promise);
            });
    }

    /**
     * Poll the next batch of items from the queue.
     *
     * Polls up to batchSize items from the remaining queue. If fewer items
     * remain than batchSize, returns all remaining items. Uses thread-safe
     * poll() operation to remove items from the queue.
     *
     * @return List of items for the current batch (maybe smaller than batchSize)
     */
    private List<T> pollBatchFromQueue()
    {
        List<T> batch = new ArrayList<>();

        for (int i = 0; i < batchSize && !remainingItems.isEmpty(); i++)
        {
            T item = remainingItems.poll();

            if (item != null)
            {
                batch.add(item);
            }
        }

        return batch;
    }

    /**
     * Process a single batch of items.
     *
     * This abstract method must be implemented by subclasses to define
     * the actual batch processing logic. The method should process all
     * items in the batch and return a Future containing the results.
     *
     * The implementation should:
     * - Process all items in the batch
     * - Return results as JsonArray
     * - Handle item-level errors appropriately
     * - Use async/non-blocking operations where possible
     *
     * @param batch List of items to process in this batch
     * @return Future containing JsonArray of results from processing this batch
     */
    protected abstract Future<JsonArray> processBatch(List<T> batch);

    /**
     * Handle batch processing failure.
     *
     * This method is called when processBatch() fails. Subclasses can override
     * this method to implement custom error handling logic such as:
     * - Tracking failed items
     * - Logging detailed error information
     * - Updating failure counters
     * - Notifying external systems
     *
     * Default implementation: logs error only (already logged by processNext)
     *
     * Note: After this method returns, processing continues with the next batch
     * (fail-tolerant behavior). The failed batch is not retried automatically.
     *
     * @param batch The batch that failed to process
     * @param cause The exception that caused the failure
     */
    protected abstract void handleBatchFailure(List<T> batch, Throwable cause);

    /**
     * Get the total number of items initially queued for processing.
     *
     * @return Total number of items
     */
    public int getTotalItems()
    {
        return totalItems;
    }

    /**
     * Get the number of batches processed so far.
     *
     * @return Number of batches processed
     */
    public int getProcessedBatches()
    {
        return processedBatches;
    }

    /**
     * Get the number of items remaining in the queue.
     *
     * @return Number of items not yet processed
     */
    public int getRemainingCount()
    {
        return remainingItems.size();
    }
}


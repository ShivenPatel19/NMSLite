package com.nmslite.core;

import io.vertx.core.Future;

import io.vertx.core.WorkerExecutor;

import io.vertx.core.json.JsonArray;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.ArrayList;

import java.util.List;

/**
 * Parallel batch processor with backpressure handling using Vert.x WorkerExecutor.
 *
 * <p>Splits items into batches and processes them in parallel using a dedicated worker pool.
 * WorkerExecutor automatically queues batches when all threads are busy, providing natural
 * backpressure without manual queue management.</p>
 *
 * <p>Subclasses must implement processBatch() to define batch processing logic.
 * Optionally override handleBatchFailure() for custom error handling.</p>
 *
 * @param <T> Type of items to process in batches
 */
public abstract class ParallelBatchProcessor<T>
{

    private static final Logger logger = LoggerFactory.getLogger(ParallelBatchProcessor.class);

    private final List<T> items;

    private final int batchSize;

    private final WorkerExecutor workerExecutor;

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
    }

    /**
     * Process all batches in parallel using WorkerExecutor.
     *
     * <p>Splits items into batches, submits all to WorkerExecutor, waits for completion,
     * and returns aggregated results. WorkerExecutor handles backpressure automatically.</p>
     *
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

            for (var batch : batches)
            {
                var batchFuture = processBatchAsync(batch);

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

                        logger.info("Parallel batch processing completed: {} total results", allResults.size());

                        return allResults;
                    })
                    .recover(throwable ->
                    {
                        logger.error("Error in Future.all composition: {}", throwable.getMessage());

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
     *
     * <p>Submits all batches immediately and returns. No result collection or waiting.
     * Each batch processes independently in background.</p>
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
            for (var batch : batches)
            {
                processBatchAsync(batch);
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
     * Split items into batches.
     *
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
            logger.error("Error in creating batches: {}", exception.getMessage());

            return new ArrayList<>();
        }
    }

    /**
     * Process a single batch asynchronously using WorkerExecutor.
     *
     * @param batch List of items in this batch
     * @return Future containing results from this batch
     */
    private Future<JsonArray> processBatchAsync(List<T> batch)
    {
        try
        {
            return workerExecutor.executeBlocking(() ->
                    {
                        try
                        {
                            logger.debug("Processing batch ({} items)", batch.size());

                            // Call subclass implementation (blocking operation allowed in worker thread)
                            var results = processBatch(batch);

                            logger.debug("Batch completed successfully ({} results)", results != null ? results.size() : 0);

                            return results;
                        }
                        catch (Exception exception)
                        {
                            logger.error("Batch processing failed: {}", exception.getMessage());

                            handleBatchFailure(batch, exception);

                            // Return empty results for failed batch (fail-tolerant)
                            return new JsonArray();
                        }
                    },
                    false  // ordered = false (allows parallel execution)
            )
            .onSuccess(results ->
                    logger.debug("Batch Future succeeded ({} results)", results != null ? results.size() : 0))
            .onFailure(throwable ->
            {
                logger.error("Batch Future failed: {}", throwable.getMessage());

                handleBatchFailure(batch, throwable);
            })
            .recover(throwable ->
            {
                logger.debug("Recovering from batch failure");

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
     * Process a single batch of items (BLOCKING operation).
     *
     * <p>Called from WorkerExecutor thread, so blocking operations are allowed.
     * Subclasses must implement this to define batch processing logic.</p>
     *
     * @param batch List of items to process in this batch
     * @return JsonArray of results from processing this batch
     */
    protected abstract JsonArray processBatch(List<T> batch);

    /**
     * Handle batch processing failure.
     *
     * <p>Called when processBatch() fails. Subclasses can override for custom error handling.
     * Processing continues with remaining batches (fail-tolerant).</p>
     *
     * @param batch The batch that failed to process
     * @param cause The exception that caused the failure
     */
    protected abstract void handleBatchFailure(List<T> batch, Throwable cause);

}


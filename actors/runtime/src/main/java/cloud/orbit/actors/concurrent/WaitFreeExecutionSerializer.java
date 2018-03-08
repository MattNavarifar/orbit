/*
 Copyright (C) 2016 Electronic Arts Inc.  All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:

 1.  Redistributions of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.
 2.  Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the distribution.
 3.  Neither the name of Electronic Arts, Inc. ("EA") nor the names of
     its contributors may be used to endorse or promote products derived
     from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY ELECTRONIC ARTS AND ITS CONTRIBUTORS "AS IS" AND ANY
 EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL ELECTRONIC ARTS OR ITS CONTRIBUTORS BE LIABLE FOR ANY
 DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package cloud.orbit.actors.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cloud.orbit.actors.runtime.InternalUtils;
import cloud.orbit.concurrent.Task;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Ensures that only a single task is executed at each time.
 *
 * @author Daniel Sperry
 */
public class WaitFreeExecutionSerializer implements ExecutionSerializer, Executor
{
    private static final Logger logger = LoggerFactory.getLogger(WaitFreeExecutionSerializer.class);
    private static final boolean DEBUG_ENABLED = logger.isDebugEnabled();

    private static final AtomicIntegerFieldUpdater<WaitFreeExecutionSerializer> lockUpdater = AtomicIntegerFieldUpdater.newUpdater(WaitFreeExecutionSerializer.class, "lock");
    private static final AtomicIntegerFieldUpdater<WaitFreeExecutionSerializer> sizeUpdater = AtomicIntegerFieldUpdater.newUpdater(WaitFreeExecutionSerializer.class, "size");

    private final ExecutorService executorService;
    private final ConcurrentLinkedQueue<Supplier<Task<?>>> queue = new ConcurrentLinkedQueue<>();

    @SuppressWarnings("FieldCanBeLocal")
    private volatile int lock = 0;
    @SuppressWarnings("FieldCanBeLocal")
    private volatile int size = 0;
    private final Object key;

    public WaitFreeExecutionSerializer(final ExecutorService executorService)
    {
        this(executorService, null);
    }

    public WaitFreeExecutionSerializer(final ExecutorService executorService, Object key)
    {
        this.executorService = executorService;
        this.key = key;
    }

    @Override
    public <R> Task<R> executeSerialized(Supplier<Task<R>> taskSupplier, int maxQueueSize)
    {
        final Task<R> completion = new Task<>();

        int queueSize = size;
        if (DEBUG_ENABLED && queueSize >= maxQueueSize / 10)
        {
            logger.debug("Queued " + queueSize + " / " + maxQueueSize + " for " + key);
        }

        final long timeAddedToQueue = System.currentTimeMillis();
        if (queueSize >= maxQueueSize || !queue.add(getTaskSupplier(taskSupplier, completion, timeAddedToQueue)))
        {
            throw new IllegalStateException(String.format("Queue full for %s (%d > %d)", key, queue.size(), maxQueueSize));
        }

        // managing the size like this to avoid using ConcurrentLinkedQueue.size()
        sizeUpdater.incrementAndGet(this);

        tryExecute(false);

        return completion;
    }

    private <R> Supplier<Task<?>> getTaskSupplier(final Supplier<Task<R>> taskSupplier, final Task<R> completion, final long timeAddedToQueue)
    {
        return () ->
        {
            long timePoppedFromQueue = System.currentTimeMillis();
            Task<R> source = InternalUtils.safeInvoke(taskSupplier);
            long timeSelfExecutionComplete = System.currentTimeMillis();

            addedToQueue(timeAddedToQueue, source);
            onExecutionStarted(source, timeAddedToQueue, timePoppedFromQueue, timeSelfExecutionComplete);

            InternalUtils.linkFutures(source, completion);
            return source;
        };
    }

    @Override
    public boolean isBusy()
    {
        return lock == 1 || !queue.isEmpty();
    }

    /**
     * @param local defines if can execute in the current thread
     */
    private void tryExecute(boolean local)
    {
        do
        {
            if (!lock())
            {
                // some other thread has the lock and it is now responsible for draining the queue.
                return;
            }

            // while there is something in the queue
            final Supplier<Task<?>> toRun = queue.poll();
            if (toRun != null)
            {
                sizeUpdater.decrementAndGet(this);
                try
                {
                    Task<?> taskFuture;
                    // isDone() must be checked in same thread after toRun is executed to avoid race condition
                    if (local)
                    {
                        taskFuture = toRun.get();
                        executedFromQueue(taskFuture);
                    }
                    else
                    {
                        // execute in another thread
                        final Task<?> task = new Task<>();
                        executorService.execute(() -> {
                            wrapExecution(toRun, task);
                            // When a method is reentrant, Execution returns Task.fromValue(null); instead of result and WaitFreeExecutionSerializer should continue with other tasks.
                            // However, when tryExecute(false) is invoked the call to task.isDone() is done after executing the task (in a separate thread).
                            // This causes a race condition where the task execution in separated thread might not finish before the isDone() check, WaitFreeExecutionSerializer is blocked
                            // waiting for task completion, while the continuation is actually queued to the serializer and will never run because of the wait.
                            if (!task.isDone())
                            {
                                // this will run whenComplete in another thread
                                task.whenCompleteAsync(createOnInvocationCompleteHandler(task), executorService);
                                // returning without unlocking, onComplete will do it;
                                return;
                            }

                            onExecutionComplete(task);
                            unlock();
                            tryExecute(true);
                        });
                        return;
                    }

                    if (taskFuture != null)
                    {
                        if (taskFuture.isDone()) {
                            onExecutionComplete(taskFuture);
                        }
                        else
                        {
                            // this will run whenComplete in another thread
                            taskFuture.whenCompleteAsync(createOnInvocationCompleteHandler(taskFuture));
                            // returning without unlocking, onComplete will do it;
                            return;
                        }
                    }
                }
                catch (Throwable ex)
                {
                    try
                    {
                        logger.error("Error executing action", ex);
                    }
                    catch (Throwable ex2)
                    {
                        // just to be on the safe side... loggers can fail...
                        ex2.printStackTrace();
                        ex.printStackTrace();
                    }
                }
                // was executed immediately
                // unlock
            }
            unlock();
        } while (!queue.isEmpty());
    }

    /**
     * @deprecated This method's name is deceptive: it is executed when the task is popped from the queue for execution,
     * not when the task is actually added to the queue. Prefer the newer onExecutionStarted(), onExecutionComplete().
     */
    @Deprecated
    protected <R> void addedToQueue(final long timeAddedToQueue, final Task<R> source)
    {
    }

    @Deprecated
    protected void executedFromQueue(final Task<?> task)
    {
    }

    /**
     * Called immediately after an actor invocation has been executed and returned a Task. Provides timestamps for when
     * that invocation entered the actor's queue, was popped for execution, and when that invocation returned its Task
     * (the returned Task may or may not be complete yet).
     *
     * @param task A Task as returned from the actor's method. In the `@Reentrant` case, this is `Task.fromValue(null)`.
     * @param timeAddedToQueue A timestamp taken when the invocation was enqueued.
     * @param timePoppedFromQueue A timestamp taken when the invocation was pulled out of the queue to be executed.
     * @param timeSelfExecutionComplete A timestamp taken when the invocation had finished returning a Task that
     *                                  represents any asynchronous computation.
     */
    protected void onExecutionStarted(
            final Task<?> task,
            final long timeAddedToQueue,
            final long timePoppedFromQueue,
            final long timeSelfExecutionComplete
    ) {
        // do-nothing: this is an extension point
    }

    /**
     * Called immediately after an actor invocation's returned Task has become complete (whether normally or
     * exceptionally), but before the execution serializer has started the next Task in the queue.
     *
     * NOTE: Methods that have been annotated with `@Reentrant` will call this method immediately, before the underlying
     * execution is actually complete.
     *
     * @param task A Task as returned from the actor's method. In the `@Reentrant` case, this is `Task.fromValue(null)`.
     */
    protected void onExecutionComplete(Task<?> task)
    {
        // do-nothing: this is an extension point
    }

    private void wrapExecution(final Supplier<Task<?>> toRun, final Task<?> taskFuture)
    {
        try
        {
            final Task<?> task = (Task) toRun.get();
            executedFromQueue(task);
            if (task == null || task.isDone())
            {
                taskFuture.complete(null);
            }
            else
            {
                task.whenComplete((r, e) -> {
                    if (e != null)
                    {
                        taskFuture.completeExceptionally(e);
                    }
                    else
                    {
                        taskFuture.complete(null);
                    }
                });
            }
        }
        catch (Exception e)
        {
            taskFuture.completeExceptionally(e);
        }
    }

    private void unlock()
    {
        if (!lockUpdater.compareAndSet(this, 1, 0))
        {
            logger.error("Unlocking without having the lock");
        }
    }

    private boolean lock()
    {
        return lockUpdater.compareAndSet(this, 0, 1);
    }

    private <T> BiConsumer<T, Throwable> createOnInvocationCompleteHandler(Task<?> task) {
        return (result, error) -> {
            onExecutionComplete(task);
            this.whenCompleteAsync(result, error);
        };
    }

    private <T> void whenCompleteAsync(T result, Throwable error)
    {
        // unlock
        unlock();
        // double take:
        // try executing again, in case some new data arrived
        tryExecute(true);
    }

    @Override
    public void execute(final Runnable command)
    {
        executeSerialized(() -> {
            command.run();
            return Task.done();
        }, Integer.MAX_VALUE);
    }
}
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.event.impl.jobs.queues;

import java.util.Set;

import org.apache.sling.event.impl.jobs.JobHandler;
import org.apache.sling.event.impl.jobs.config.InternalQueueConfiguration;

/**
 * An ordered job queue is processing the queue FIFO in a serialized
 * way. If a job fails it is rescheduled and the reschedule is processed
 * next - this basically means that failing jobs block the queue
 * until they are finished!
 */
public final class OrderedJobQueue extends AbstractJobQueue {

    /** Object to sync operations within this queue. */
    private final Object syncLock = new Object();

    /** Sleep delay if job needs rescheduling. */
    private long sleepDelay = -1;

    public OrderedJobQueue(final String name,
                           final InternalQueueConfiguration config,
                           final QueueServices services,
                           final Set<String> topics) {
        super(name, config, services, topics);
    }

    @Override
    protected void start(final JobHandler handler) {
        synchronized ( this.syncLock ) {
            if ( this.executeJob(handler) ) {
                this.isWaiting = true;
                this.logger.debug("Job queue {} is waiting for finish.", this.queueName);
                while ( this.isWaiting ) {
                    try {
                        this.syncLock.wait();
                    } catch (final InterruptedException e) {
                        this.ignoreException(e);
                        Thread.currentThread().interrupt();
                    }
                }
                if ( this.sleepDelay > 0 ) {
                    final long waitingTime = this.sleepDelay;
                    this.sleepDelay = -1;
                    final long startTime = System.currentTimeMillis();
                    this.logger.debug("Job queue {} is sleeping {}ms for retry.", this.queueName, waitingTime);
                    this.isWaiting = true;
                    while ( this.isWaiting ) {
                        try {
                            this.syncLock.wait(waitingTime);
                            if ( System.currentTimeMillis() >= startTime + waitingTime ) {
                                this.isWaiting = false;
                            }
                        } catch (final InterruptedException e) {
                            this.ignoreException(e);
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                this.logger.debug("Job queue {} is continuing.", this.queueName);
            }
        }
    }

    @Override
    protected void reschedule(final JobHandler handler) {
        super.reschedule(handler);
        this.sleepDelay = this.getRetryDelay(handler);
    }

    @Override
    protected void notifyFinished(final boolean reschedule) {
        this.logger.debug("Notifying job queue {} to continue processing.", this.queueName);
        synchronized ( this.syncLock ) {
            this.isWaiting = false;
            if ( !reschedule ) {
                this.sleepDelay = -1;
            }
            this.syncLock.notify();
        }
    }
}


/*
 * Copyright 2025, AutoMQ HK Limited.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.log.streamaspect;

import com.automq.stream.api.Stream;
import com.automq.stream.api.StreamClient;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Elastic log dimension stream manager.
 */
public class ElasticLogStreamManager {
    private final Map<String, LazyStream> streamMap = new ConcurrentHashMap<>();
    private final StreamClient streamClient;
    private final int replicaCount;
    private final long epoch;
    private final Map<String, String> tags;
    private final boolean snapshotRead;
    /**
     * inner listener for created LazyStream
     */
    private final LazyStreamStreamEventListener innerListener = new LazyStreamStreamEventListener();
    /**
     * outer register listener
     */
    private ElasticStreamEventListener outerListener;

    public ElasticLogStreamManager(Map<String, Long> streams, StreamClient streamClient, int replicaCount, long epoch, Map<String, String> tags, boolean snapshotRead) throws IOException {
        this.streamClient = streamClient;
        this.replicaCount = replicaCount;
        this.epoch = epoch;
        this.tags = tags;
        this.snapshotRead = snapshotRead;
        for (Map.Entry<String, Long> entry : streams.entrySet()) {
            String name = entry.getKey();
            long streamId = entry.getValue();
            LazyStream stream = new LazyStream(name, streamId, streamClient, replicaCount, epoch, tags, snapshotRead);
            stream.setListener(innerListener);
            streamMap.put(name, stream);
        }
    }

    public LazyStream getStream(String name) throws IOException {
        if (streamMap.containsKey(name)) {
            return streamMap.get(name);
        }
        if (snapshotRead) {
            throw new IllegalStateException("snapshotRead mode can not create stream");
        }
        LazyStream lazyStream = new LazyStream(name, LazyStream.NOOP_STREAM_ID, streamClient, replicaCount, epoch, tags, snapshotRead);
        lazyStream.setListener(innerListener);
        // pre-create log and tim stream cause of their high frequency of use.
        boolean warmUp = "log".equals(name) || "tim".equals(name);
        if (warmUp) {
            lazyStream.warmUp();
        }
        streamMap.put(name, lazyStream);
        return lazyStream;
    }

    public Map<String, Stream> streams() {
        return Collections.unmodifiableMap(streamMap);
    }

    public void putStreamIfAbsent(String name, long streamId) {
        streamMap.computeIfAbsent(name, n -> {
            try {
                return new LazyStream(name, streamId, streamClient, replicaCount, epoch, tags, snapshotRead);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void setListener(ElasticStreamEventListener listener) {
        this.outerListener = listener;
    }

    /**
     * Directly close all streams.
     */
    public CompletableFuture<Void> close() {
        return CompletableFuture.allOf(streamMap.values().stream().map(LazyStream::close).toArray(CompletableFuture[]::new));
    }

    class LazyStreamStreamEventListener implements ElasticStreamEventListener {
        @Override
        public void onEvent(long streamId, ElasticStreamMetaEvent event) {
            Optional.ofNullable(outerListener).ifPresent(listener -> listener.onEvent(streamId, event));
        }
    }
}

package com.bergerkiller.bukkit.lightcleaner.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.bukkit.World;

import com.bergerkiller.bukkit.common.chunk.ForcedChunk;
import com.bergerkiller.bukkit.common.utils.CommonUtil;

/**
 * Wraps a {@link ForcedChunk} but delays releasing the chunk
 * for a few ticks. This prevents a problem where unloading chunks
 * right after updating lighting data causes a server crash.
 */
public class DelayClosedForcedChunk extends ForcedChunk {
    public static final int UNLOAD_TICK_DELAY = 100; // 5 seconds

    private DelayClosedForcedChunk() {
        super(null);
    }

    /**
     * Takes over ownership of closing a chunk from another ForcedChunk.
     * The input forced chunk is closed.
     * 
     * @param chunk Input forced chunk, is closed
     */
    public DelayClosedForcedChunk(ForcedChunk chunk) {
        super(null);
        chunk.move(this);
    }

    @Override
    public void close() {
        ForcedChunk closed = ForcedChunk.none();
        closed.move(this);
        if (!closed.isNone()) {
            Cache.store(closed);
        }
    }

    /**
     * Performs a routine cleanup, unloading chunks that haven't been used in a while
     */
    public static void cleanup() {
        Cache.cleanup();
    }

    /**
     * Instantly closes all forced chunks that were delayed
     */
    public static void clear() {
        Cache.clear();
    }

    /**
     * See {@link ForcedChunk#none()}
     * 
     * @return already closed forced chunk
     */
    public static DelayClosedForcedChunk none() {
        return new DelayClosedForcedChunk();
    }

    /**
     * Stores previously closed forced chunks, which will be routinely closed by
     * a background task
     */
    private static class Cache {
        private static final Map<Key, DelayClosedChunk> _cache = new HashMap<Key, DelayClosedChunk>();

        public static void store(ForcedChunk chunk) {
            DelayClosedChunk previous;
            synchronized (_cache) {
                previous = _cache.put(new Key(chunk), new DelayClosedChunk(chunk));
            }
            if (previous != null) {
                previous.chunk.close();
            }
        }

        public static void clear() {
            List<DelayClosedChunk> chunks;
            synchronized (_cache) {
                chunks = new ArrayList<DelayClosedChunk>(_cache.values());
                _cache.clear();
            }
            for (DelayClosedChunk chunk : chunks) {
                chunk.chunk.close();
            }
        }

        public static void cleanup() {
            int currentTime = CommonUtil.getServerTicks();
            List<ForcedChunk> chunksToClose = Collections.emptyList();

            // Collect expired chunks while synchronized
            synchronized (_cache) {
                Iterator<DelayClosedChunk> iter = _cache.values().iterator();
                while (iter.hasNext()) {
                    DelayClosedChunk chunk = iter.next();
                    if (currentTime >= chunk.expire) {
                        iter.remove();
                        if (chunksToClose.isEmpty()) {
                            chunksToClose = new ArrayList<ForcedChunk>();
                        }
                        chunksToClose.add(chunk.chunk);
                    }
                }
            }

            // Close the chunks outside of the synchronized block
            // They unload, which might cause contention due to events
            for (ForcedChunk chunk : chunksToClose) {
                chunk.close();
            }
        }

        private static final class Key {
            public final World world;
            public final int x;
            public final int z;

            public Key(ForcedChunk chunk) {
                this.world = chunk.getWorld();
                this.x = chunk.getX();
                this.z = chunk.getZ();
            }

            @Override
            public int hashCode() {
                return this.x * 31 + this.z;
            }

            @Override
            public boolean equals(Object o) {
                if (o instanceof Key) {
                    Key other = (Key) o;
                    return other.x == this.x &&
                           other.z == this.z &&
                           other.world == this.world;
                } else {
                    return false;
                }
            }
        }

        private static final class DelayClosedChunk {
            public final ForcedChunk chunk;
            public final int expire;

            public DelayClosedChunk(ForcedChunk chunk) {
                this.chunk = chunk;
                this.expire = CommonUtil.getServerTicks() + UNLOAD_TICK_DELAY;
            }
        }
    }
}

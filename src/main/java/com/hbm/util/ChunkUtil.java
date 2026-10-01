package com.hbm.util;

import com.hbm.config.GeneralConfig;
import com.hbm.handler.threading.BombForkJoinPool;
import com.hbm.interfaces.BitMask;
import com.hbm.interfaces.ServerThread;
import com.hbm.interfaces.ThreadSafeMethod;
import com.hbm.lib.Library;
import com.hbm.lib.RefStrings;
import com.hbm.lib.internal.UnsafeHolder;
import com.hbm.main.MainRegistry;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityLockableLoot;
import net.minecraft.util.BitArray;
import net.minecraft.util.IntIdentityHashBiMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.BlockStatePaletteHashMap;
import net.minecraft.world.chunk.BlockStatePaletteLinear;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IBlockStatePalette;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.hbm.lib.internal.UnsafeHolder.U;

/**
 * High-performance, non-blocking utilities for working with Minecraft 1.12.2 {@link Chunk} internals.
 *
 * <p>This is a reduced port of the community edition's {@code ChunkUtil} containing only the pieces the
 * parallel nuclear explosion executor needs: a concurrent mirror map of loaded chunks, and copy-on-write
 * sub-chunk carving with a single CAS publish. The community edition's {@code NonBlockingHashMapLong} is
 * replaced by {@link ConcurrentHashMap}, and its {@code OffHeapBitSet} by {@link AtomicLongBitSet}.</p>
 */
@Mod.EventBusSubscriber(modid = RefStrings.MODID)
public final class ChunkUtil {

    private static final long ARR_BASE = U.arrayBaseOffset(ExtendedBlockStorage[].class);
    private static final long ARR_SCALE = U.arrayIndexScale(ExtendedBlockStorage[].class);
    private static final long UNLOAD_QUEUED_OFFSET = UnsafeHolder.fieldOffset(Chunk.class, "unloadQueued", "field_189550_d");
    private static final long BSL_STATES_OFFSET = UnsafeHolder.fieldOffset(BlockStatePaletteLinear.class, "states", "field_186042_a");
    private static final long BSL_ARRAY_SIZE_OFFSET = UnsafeHolder.fieldOffset(BlockStatePaletteLinear.class, "arraySize", "field_186045_d");
    private static final long BSHM_MAP_OFFSET = UnsafeHolder.fieldOffset(BlockStatePaletteHashMap.class, "statePaletteMap", "field_186046_a");
    private static final long IIHBM_VALUES_OFFSET = UnsafeHolder.fieldOffset(IntIdentityHashBiMap.class, "values", "field_186818_b");
    private static final long IIHBM_INTKEYS_OFFSET = UnsafeHolder.fieldOffset(IntIdentityHashBiMap.class, "intKeys", "field_186819_c");
    private static final long IIHBM_BYID_OFFSET = UnsafeHolder.fieldOffset(IntIdentityHashBiMap.class, "byId", "field_186820_d");
    private static final long IIHBM_NEXTFREE_OFFSET = UnsafeHolder.fieldOffset(IntIdentityHashBiMap.class, "nextFreeIndex", "field_186821_e");
    private static final long IIHBM_MAPSIZE_OFFSET = UnsafeHolder.fieldOffset(IntIdentityHashBiMap.class, "mapSize", "field_186822_f");

    private static final IBlockState AIR_DEFAULT_STATE = Blocks.AIR.getDefaultState();

    /** Dimension -> active task count (used to decide when to build/tear down the mirror map). */
    private static final Int2IntOpenHashMap activeTask = new Int2IntOpenHashMap();

    /** Dimension -> (chunkPos long key -> Chunk) mirror. Only present while there are active tasks. */
    private static final ConcurrentHashMap<Integer, ConcurrentHashMap<Long, Chunk>> chunkMap = new ConcurrentHashMap<>();

    /** Global reference count across all dimensions indicating how many concurrent tasks are active. */
    private static int refCounter = 0;

    private ChunkUtil() {
    }

    /** Clear stored contents before replacing a tile entity's block through vanilla's lifecycle. */
    @ServerThread
    public static boolean replaceEmptied(World world, BlockPos pos, IBlockState replacement, int flags) {
        IBlockState old = world.getBlockState(pos);
        if (old == replacement) return false;
        if (old.getBlock().hasTileEntity(old)) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof TileEntityLockableLoot) {
                TileEntityLockableLoot loot = (TileEntityLockableLoot) tile;
                loot.setLootTable(null, 0);
            }
            if (tile instanceof IInventory) {
                IInventory inventory = (IInventory) tile;
                inventory.clear();
            }
        }
        return world.setBlockState(pos, replacement, flags);
    }

    /**
     * Build (or reference) the mirror map for the given dimension and increment its task count.
     * Must be called from the server thread.
     */
    @ServerThread
    public static ConcurrentHashMap<Long, Chunk> acquireMirrorMap(WorldServer world) {
        int key = world.provider.getDimension();
        ConcurrentHashMap<Long, Chunk> thisDim;
        if (activeTask.addTo(key, 1) == 0) {
            thisDim = new ConcurrentHashMap<>(4096);
            // This parallel traversal assumes the server thread is quiescent for this world's provider
            world.getChunkProvider().loadedChunks.values().parallelStream()
                    .forEach(chunk -> thisDim.put(ChunkPos.asLong(chunk.x, chunk.z), chunk));
            chunkMap.put(key, thisDim);
        } else {
            thisDim = chunkMap.get(key);
        }
        refCounter++;
        if (GeneralConfig.enableExtendedLogging) {
            MainRegistry.logger.info("Acquired mirror map for dimension {}. Active tasks of this dim = {}, refCounter = {}.",
                    key, activeTask.get(key), refCounter);
        }
        return Objects.requireNonNull(thisDim);
    }

    /** Decrement the dimension task count and, if it hits zero, drop the mirror map for that dimension. */
    @ServerThread
    public static void releaseMirrorMap(WorldServer world) {
        int key = world.provider.getDimension();
        if (activeTask.addTo(key, -1) == 1) chunkMap.remove(key);
        refCounter--;
        if (GeneralConfig.enableExtendedLogging) {
            MainRegistry.logger.info("Released mirror map for dimension {}. Active tasks of this dim = {}, refCounter = {}.",
                    key, activeTask.get(key), refCounter);
        }
    }

    /** Lookup a loaded chunk from the mirror map, clearing its unloadQueued flag to reduce unload races. */
    @ThreadSafeMethod
    public static Chunk getLoadedChunk(Map<Long, ? extends Chunk> loaded, long chunkPos) {
        Chunk chunk = loaded.get(chunkPos);
        if (chunk == null) return null;
        U.putBooleanVolatile(chunk, UNLOAD_QUEUED_OFFSET, false);
        return chunk;
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (refCounter == 0) return;
        Chunk chunk = event.getChunk();
        World world = chunk.getWorld();
        if (world.isRemote) return;
        int key = world.provider.getDimension();
        if (activeTask.get(key) == 0) return;
        ConcurrentHashMap<Long, Chunk> dimMap = chunkMap.get(key);
        if (dimMap != null) dimMap.put(ChunkPos.asLong(chunk.x, chunk.z), chunk);
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (refCounter == 0) return;
        Chunk chunk = event.getChunk();
        World world = chunk.getWorld();
        if (world.isRemote) return;
        int key = world.provider.getDimension();
        ConcurrentHashMap<Long, Chunk> dimMap = chunkMap.get(key);
        if (dimMap != null) dimMap.remove(ChunkPos.asLong(chunk.x, chunk.z));
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world.isRemote) return;
        int key = world.provider.getDimension();
        // Cancel any active bomb jobs before tearing down the mirror map for this dimension.
        BombForkJoinPool.onWorldUnload(world);
        if (GeneralConfig.enableExtendedLogging)
            MainRegistry.logger.info("Dimension {} unloaded with {} active tasks. refCounter = {}",
                    key, activeTask.get(key), refCounter);
        activeTask.put(key, 0);
        chunkMap.remove(key);
    }

    public static void onServerStopped() {
        chunkMap.clear();
        activeTask.clear();
        if (GeneralConfig.enableExtendedLogging)
            MainRegistry.logger.info("Server stopping with {} active tasks, refCounter = {}",
                    Arrays.stream(activeTask.values().toIntArray()).sum(), refCounter);
        refCounter = 0;
    }

    /** Make a deep copy of a {@link BlockStateContainer}, including its palette and backing bit-storage. */
    @ThreadSafeMethod
    public static BlockStateContainer copyOf(BlockStateContainer srcData) {
        int bits = srcData.bits;
        IBlockStatePalette srcPalette = srcData.palette;
        BlockStateContainer copied = UnsafeHolder.allocateInstance(BlockStateContainer.class);
        copied.bits = bits;

        if (bits <= 4) {
            copied.palette = new BlockStatePaletteLinear(bits, copied);
            int arraySize = U.getInt(srcPalette, BSL_ARRAY_SIZE_OFFSET);
            U.putInt(copied.palette, BSL_ARRAY_SIZE_OFFSET, arraySize);
            IBlockState[] srcStates = (IBlockState[]) U.getReference(srcPalette, BSL_STATES_OFFSET);
            IBlockState[] dstStates = (IBlockState[]) U.getReference(copied.palette, BSL_STATES_OFFSET);
            System.arraycopy(srcStates, 0, dstStates, 0, arraySize);
        } else if (bits <= 8) {
            copied.palette = new BlockStatePaletteHashMap(bits, copied);
            Object srcMap = U.getReference(srcPalette, BSHM_MAP_OFFSET);
            Object dstMap = U.getReference(copied.palette, BSHM_MAP_OFFSET);

            int nextFree = U.getInt(srcMap, IIHBM_NEXTFREE_OFFSET);
            int mapSize = U.getInt(srcMap, IIHBM_MAPSIZE_OFFSET);
            U.putInt(dstMap, IIHBM_NEXTFREE_OFFSET, nextFree);
            U.putInt(dstMap, IIHBM_MAPSIZE_OFFSET, mapSize);

            Object[] srcValues = (Object[]) U.getReference(srcMap, IIHBM_VALUES_OFFSET);
            int[] srcIntKeys = (int[]) U.getReference(srcMap, IIHBM_INTKEYS_OFFSET);
            Object[] srcById = (Object[]) U.getReference(srcMap, IIHBM_BYID_OFFSET);

            U.putReference(dstMap, IIHBM_VALUES_OFFSET, srcValues.clone());
            U.putReference(dstMap, IIHBM_INTKEYS_OFFSET, srcIntKeys.clone());
            U.putReference(dstMap, IIHBM_BYID_OFFSET, srcById.clone());
        } else {
            copied.palette = BlockStateContainer.REGISTRY_BASED_PALETTE;
        }

        BitArray srcStorage = srcData.storage;
        copied.storage = new BitArray(bits, 4096);
        long[] srcLongs = srcStorage.getBackingLongArray();
        long[] dstLongs = copied.storage.getBackingLongArray();
        System.arraycopy(srcLongs, 0, dstLongs, 0, srcLongs.length);
        return copied;
    }

    /** @return a deep copy of {@code src} */
    @ThreadSafeMethod
    public static ExtendedBlockStorage copyOf(ExtendedBlockStorage src) {
        ExtendedBlockStorage dst = UnsafeHolder.allocateInstance(ExtendedBlockStorage.class);
        dst.yBase = src.yBase;
        dst.data = copyOf(src.getData());
        dst.blockLight = new NibbleArray(src.getBlockLight().getData().clone());
        dst.skyLight = src.skyLight != null ? new NibbleArray(src.skyLight.getData().clone()) : null;
        dst.blockRefCount = src.blockRefCount;
        dst.tickRefCount = src.tickRefCount;
        return dst;
    }

    private static boolean checkNeighbor(ConcurrentMap<Long, Chunk> loaded, int chunkX, int chunkZ, int subY, int height,
                                         ExtendedBlockStorage[] srcs, NeighborCache nc, int x, int y, int z, BitMask localMask) {
        if (x >= 0 && x <= 15 && y >= 0 && y <= 15 && z >= 0 && z <= 15) {
            if (localMask != null) {
                int nIdx = Library.packLocal(x, y, z);
                if (localMask.get(nIdx)) return false;
            }
            ExtendedBlockStorage src = srcs[subY];
            return src != null && !src.isEmpty() && src.get(x, y, z).getBlock() != Blocks.AIR;
        }

        if (y < 0) {
            if (subY == 0) return false;
            ExtendedBlockStorage below = srcs[subY - 1];
            return below != null && !below.isEmpty() && below.get(x, 15, z).getBlock() != Blocks.AIR;
        }
        if (y > 15) {
            if (subY >= (height >> 4) - 1) return false;
            ExtendedBlockStorage above = srcs[subY + 1];
            return above != null && !above.isEmpty() && above.get(x, 0, z).getBlock() != Blocks.AIR;
        }
        if (x < 0) {
            if (nc.negX == null) nc.negX = getLoadedEBS(loaded, ChunkPos.asLong(chunkX - 1, chunkZ));
            if (nc.negX != null) {
                ExtendedBlockStorage n = nc.negX[subY];
                return n != null && !n.isEmpty() && n.get(15, y, z).getBlock() != Blocks.AIR;
            }
            return false;
        }
        if (x > 15) {
            if (nc.posX == null) nc.posX = getLoadedEBS(loaded, ChunkPos.asLong(chunkX + 1, chunkZ));
            if (nc.posX != null) {
                ExtendedBlockStorage n = nc.posX[subY];
                return n != null && !n.isEmpty() && n.get(0, y, z).getBlock() != Blocks.AIR;
            }
            return false;
        }
        if (z < 0) {
            if (nc.negZ == null) nc.negZ = getLoadedEBS(loaded, ChunkPos.asLong(chunkX, chunkZ - 1));
            if (nc.negZ != null) {
                ExtendedBlockStorage n = nc.negZ[subY];
                return n != null && !n.isEmpty() && n.get(x, y, 15).getBlock() != Blocks.AIR;
            }
            return false;
        }
        // z must >= 16
        if (nc.posZ == null) nc.posZ = getLoadedEBS(loaded, ChunkPos.asLong(chunkX, chunkZ + 1));
        if (nc.posZ != null) {
            ExtendedBlockStorage n = nc.posZ[subY];
            return n != null && !n.isEmpty() && n.get(x, y, 0).getBlock() != Blocks.AIR;
        }
        return false;
    }

    /**
     * Produce a modified copy of the target sub-chunk by carving out positions marked in the local mask.
     * Tile-entity cells are left in the section for the server-thread block replacement.
     */
    @ThreadSafeMethod
    public static ExtendedBlockStorage copyAndCarveLocal(WorldServer world, int chunkX, int chunkZ, int subY,
                                                         ExtendedBlockStorage[] srcs, BitMask localMask,
                                                         LongCollection edgeOut,
                                                         Long2ObjectMap<IBlockState> modifiedOut,
                                                         Long2ObjectMap<IBlockState> blockEntitiesOut) {
        ExtendedBlockStorage src = getEbsVolatile(srcs, subY);
        if (src == null || src.isEmpty()) return null;
        int height = world.getHeight();
        ExtendedBlockStorage dst = null;
        int airId = -1;
        NeighborCache neighbors = new NeighborCache();
        ConcurrentHashMap<Long, Chunk> loaded = chunkMap.get(world.provider.getDimension());
        int xBase = chunkX << 4, yBase = subY << 4, zBase = chunkZ << 4;
        for (int idx = localMask.nextSetBit(0); idx >= 0 && idx < 4096; idx = localMask.nextSetBit(idx + 1)) {
            int xLocal = Library.getLocalX(idx);
            int yLocal = Library.getLocalY(idx);
            int zLocal = Library.getLocalZ(idx);

            IBlockState old = src.get(xLocal, yLocal, zLocal);
            if (old.getMaterial() != Material.AIR) {
                int xGlobal = xBase | xLocal;
                int yGlobal = yBase | yLocal;
                int zGlobal = zBase | zLocal;
                long position = Library.blockPosToLong(xGlobal, yGlobal, zGlobal);
                if (old.getBlock().hasTileEntity(old)) {
                    blockEntitiesOut.put(position, old);
                    continue;
                }
                if (checkNeighbor(loaded, chunkX, chunkZ, subY, height, srcs, neighbors, xLocal - 1, yLocal, zLocal, localMask)
                        || checkNeighbor(loaded, chunkX, chunkZ, subY, height, srcs, neighbors, xLocal + 1, yLocal, zLocal, localMask)
                        || checkNeighbor(loaded, chunkX, chunkZ, subY, height, srcs, neighbors, xLocal, yLocal - 1, zLocal, localMask)
                        || checkNeighbor(loaded, chunkX, chunkZ, subY, height, srcs, neighbors, xLocal, yLocal + 1, zLocal, localMask)
                        || checkNeighbor(loaded, chunkX, chunkZ, subY, height, srcs, neighbors, xLocal, yLocal, zLocal - 1, localMask)
                        || checkNeighbor(loaded, chunkX, chunkZ, subY, height, srcs, neighbors, xLocal, yLocal, zLocal + 1, localMask)) {
                    edgeOut.add(position);
                }
                if (dst == null) {
                    dst = copyOf(src);
                    airId = dst.data.palette.idFor(AIR_DEFAULT_STATE);
                }
                dst.data.storage.setAt(idx, airId);
                dst.blockRefCount--;
                if (old.getBlock().getTickRandomly()) dst.tickRefCount--;
                Block oldBlock = old.getBlock();
                // These blocks inherit Block.breakBlock, whose only work is tile-entity removal.
                if (oldBlock != Blocks.STONE && oldBlock != Blocks.DIRT && oldBlock != Blocks.GRASS) {
                    modifiedOut.put(position, old);
                }
            }
        }
        return dst;
    }

    /** Atomically swap a sub-chunk slot using a single compare-and-swap (CAS). */
    private static boolean casEbsAt(ExtendedBlockStorage expect, ExtendedBlockStorage update,
                                    ExtendedBlockStorage[] arr, int subY) {
        long off = ARR_BASE + ((long) subY) * ARR_SCALE;
        return U.compareAndSetReference(arr, off, expect, update);
    }

    /** Load a sub-chunk slot using volatile reads. */
    private static ExtendedBlockStorage getEbsVolatile(ExtendedBlockStorage[] arr, int subY) {
        long off = ARR_BASE + ((long) subY) * ARR_SCALE;
        return (ExtendedBlockStorage) U.getReferenceVolatile(arr, off);
    }

    /** Fetch the sub-chunk array for a loaded chunk using the mirror map. */
    @ThreadSafeMethod
    public static ExtendedBlockStorage[] getLoadedEBS(Map<Long, ? extends Chunk> loaded, long chunkPos) {
        Chunk chunk = getLoadedChunk(loaded, chunkPos);
        if (chunk == null) return null;
        return chunk.getBlockStorageArray();
    }

    /** Copy only the block-state container (no light arrays). */
    @ThreadSafeMethod
    public static ExtendedBlockStorage copyBlockStates(ExtendedBlockStorage src) {
        ExtendedBlockStorage dst = UnsafeHolder.allocateInstance(ExtendedBlockStorage.class);
        dst.yBase = src.yBase;
        dst.data = copyOf(src.getData());
        // Preserve skyLight even when blockLight is absent: dropping it makes getSkyLight read 0
        // ("black"), which was the root cause of whole-chunk blackout after the conversion pass.
        dst.skyLight = src.skyLight != null ? new NibbleArray(src.skyLight.getData().clone()) : null;
        dst.blockRefCount = src.blockRefCount;
        dst.tickRefCount = src.tickRefCount;
        return dst;
    }

    @ThreadSafeMethod
    public static boolean sameBlockStates(ExtendedBlockStorage before, ExtendedBlockStorage now) {
        if (before.blockRefCount != now.blockRefCount || before.tickRefCount != now.tickRefCount) return false;
        BlockStateContainer left = before.getData();
        BlockStateContainer right = now.getData();
        if (left.bits != right.bits || !Arrays.equals(left.storage.getBackingLongArray(), right.storage.getBackingLongArray())) {
            return false;
        }
        if (left.palette == right.palette) return true;
        for (int id = 0, count = 1 << left.bits; id < count; id++) {
            if (left.palette.getBlockState(id) != right.palette.getBlockState(id)) return false;
        }
        return true;
    }

    /**
     * Create a modified copy of a sub-chunk by applying local (0..4095) overrides.
     *
     * @param toUpdate map of packed-local index ({@code x | (z << 4) | (y << 8)}) -> new state
     * @param oldStatesOut optional sink of pre-change states keyed by global packed positions
     * @return null for no-op, or a modified copy (an all-air sub-chunk for became-empty)
     */
    @ThreadSafeMethod
    public static Optional<ExtendedBlockStorage> copyAndModify(int chunkX, int chunkZ, int subY, boolean hasSky,
                                                               ExtendedBlockStorage src,
                                                               Int2ObjectMap<IBlockState> toUpdate,
                                                               Long2ObjectMap<IBlockState> oldStatesOut) {
        if (toUpdate.isEmpty()) return null;

        ExtendedBlockStorage dst = null;
        boolean anyChange = false;
        int xBase = chunkX << 4;
        int yBase = subY << 4;
        int zBase = chunkZ << 4;

        for (Int2ObjectMap.Entry<IBlockState> e : toUpdate.int2ObjectEntrySet()) {
            int packedLocal = e.getIntKey();
            int lx = Library.getLocalX(packedLocal);
            int ly = Library.getLocalY(packedLocal);
            int lz = Library.getLocalZ(packedLocal);

            IBlockState newState = e.getValue();
            if (newState == null) throw new NullPointerException("newState");

            IBlockState oldState = (src != null && !src.isEmpty()) ? src.get(lx, ly, lz) : AIR_DEFAULT_STATE;
            if (oldState == newState) continue;

            if (dst == null) {
                if (src != null && !src.isEmpty()) {
                    dst = src.blockLight == null ? copyBlockStates(src) : copyOf(src);
                } else if (src != null && src.skyLight != null) {
                    // Empty sub-chunk that still holds skyLight (sky-exposed air above the terrain):
                    // preserve it instead of creating an all-zero skyLight, which would read as "black".
                    dst = copyOf(src);
                } else {
                    if (newState.getBlock() == Blocks.AIR) continue;
                    dst = new ExtendedBlockStorage(yBase, hasSky);
                    // A NULL_BLOCK_STORAGE sub-chunk written here is air above the terrain (sky-exposed),
                    // so its skyLight must be 15, not the all-zero default. Fill it directly: world.checkLight
                    // is a no-op for freshly-loaded chunks whose neighbour chunks are not yet loaded.
                    if (hasSky && dst.skyLight != null) {
                        Arrays.fill(dst.skyLight.getData(), (byte) 0xFF);
                    }
                }
            }

            if (oldStatesOut != null) {
                int xGlobal = xBase | lx;
                int yGlobal = yBase | ly;
                int zGlobal = zBase | lz;
                oldStatesOut.put(Library.blockPosToLong(xGlobal, yGlobal, zGlobal), oldState);
            }

            dst.set(lx, ly, lz, newState);
            anyChange = true;
        }

        if (!anyChange) return null;
        return Optional.of(dst);
    }

    /**
     * Chunk-local horizontal skylight propagation. {@code World.checkLight} is a no-op for freshly
     * loaded chunks whose neighbour chunks are not yet loaded (its {@code isAreaLoaded(pos, 16)}
     * guard fails), so we relax the skyLight in place: skyLight = max(neighbour skyLight - opacity).
     */
    private static final int[] SKY_DX = { 0, 0, 0, 0, -1, 1 };
    private static final int[] SKY_DY = { 1, -1, 0, 0, 0, 0 };
    private static final int[] SKY_DZ = { 0, 0, -1, 1, 0, 0 };

    /**
     * BFS flood-fill of skylight from the sky-lit air (skylight 15, produced by
     * generateSkylightMap) through the whole connected transparent space. Unlike a fixed-pass
     * diffusion this reaches around corners and into connected-but-hidden cave/ravine spaces and
     * concave walls in O(n), giving the same result as vanilla checkLight for the skylight channel.
     * Positions are packed as (y & 255) | (x & 15) << 8 | (z & 15) << 12.
     */
    public static void propagateSkylightLocal(Chunk chunk) {
        ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
        int[] queue = new int[4096];
        int head = 0;
        int tail = 0;

        // Seed with every block that is already fully sky-lit.
        for (int subY = 0; subY < 16; subY++) {
            ExtendedBlockStorage s = storages[subY];
            if (s == null || s == Chunk.NULL_BLOCK_STORAGE || s.isEmpty()) continue;
            int baseY = subY << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (s.getSkyLight(x, y, z) >= 15) {
                            if (tail == queue.length) queue = Arrays.copyOf(queue, queue.length << 1);
                            queue[tail++] = (baseY + y) | (x << 8) | (z << 12);
                        }
                    }
                }
            }
        }
        while (head < tail) {
            int packed = queue[head++];
            int x = (packed >> 8) & 15;
            int y = packed & 255;
            int z = (packed >> 12) & 15;
            int light = getSky(storages, x, y, z);
            if (light <= 1) continue;

            for (int d = 0; d < 6; d++) {
                int nx = x + SKY_DX[d];
                int ny = y + SKY_DY[d];
                int nz = z + SKY_DZ[d];
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < 0 || ny > 255) continue;
                int nsubY = ny >> 4;
                ExtendedBlockStorage ns = storages[nsubY];
                if (ns == null || ns == Chunk.NULL_BLOCK_STORAGE || ns.isEmpty()) continue;
                int opacity = ns.get(nx, ny & 15, nz).getLightOpacity();
                if (opacity >= 15) continue; // opaque neighbour blocks skylight
                int newLight = light - Math.max(1, opacity); // air still costs 1, like vanilla
                if (newLight > ns.getSkyLight(nx, ny & 15, nz)) {
                    ns.setSkyLight(nx, ny & 15, nz, newLight);
                    if (tail == queue.length) queue = Arrays.copyOf(queue, queue.length << 1);
                    queue[tail++] = ny | (nx << 8) | (nz << 12);
                }
            }
        }
    }

    private static int getSky(ExtendedBlockStorage[] storages, int x, int y, int z) {
        if (y < 0 || y > 255) return 0;
        ExtendedBlockStorage s = storages[y >> 4];
        if (s == null || s == Chunk.NULL_BLOCK_STORAGE) return 0;
        return s.getSkyLight(x, y & 15, z);
    }

    /**
     * Unified skylight recompute for a chunk whose blocks are now final: recompute the vertical
     * skylight (generateSkylightMap) then flood-fill it horizontally into caves/ravines/concave
     * walls (propagateSkylightLocal). Call this exactly once per chunk, after the block edits.
     */
    public static void recomputeSkylight(Chunk chunk) {
        chunk.generateSkylightMap();
        propagateSkylightLocal(chunk);
    }

    /** Lazy neighbor storage cache for edge-contact checks while carving. */
    private static final class NeighborCache {
        ExtendedBlockStorage[] negX, posX, negZ, posZ;
    }
}
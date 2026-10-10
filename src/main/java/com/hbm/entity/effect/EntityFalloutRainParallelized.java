package com.hbm.entity.effect;

import com.hbm.blocks.ModBlocks;
import com.hbm.blocks.generic.BlockPowder;
import com.hbm.blocks.generic.WasteLeaves;
import com.hbm.blocks.generic.WasteLog;
import com.hbm.config.BombConfig;
import com.hbm.config.CompatibilityConfig;
import com.hbm.config.RadiationConfig;
import com.hbm.config.VersatileConfig;
import com.hbm.entity.logic.EntityChunky;
import com.hbm.handler.threading.BombForkJoinPool;
import com.hbm.interfaces.ServerThread;
import com.hbm.lib.Library;
import com.hbm.lib.internal.UnsafeHolder;
import com.hbm.main.MainRegistry;
import com.hbm.saveddata.AuxSavedData;
import com.hbm.util.ChunkUtil;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.IPlantable;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.oredict.OreDictionary;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.hbm.lib.internal.UnsafeHolder.U;

/**
 * Parallelized replacement for {@link EntityFalloutUnderGround} + {@link EntityFalloutRain}.
 *
 * <p>The conversion rules are kept identical to the two legacy entities (hardcoded if-else chains). Only the
 * execution changes: block sampling runs off-thread against a read-only mirror, and the main thread applies the
 * collected replacements via copy-on-write sub-chunks ({@link ChunkUtil#copyAndModify}).</p>
 */
public class EntityFalloutRainParallelized extends EntityChunky implements BombForkJoinPool.IJobCancellable {

    private static final DataParameter<Integer> SCALE = EntityDataManager.createKey(EntityFalloutRainParallelized.class, DataSerializers.VARINT);

    public boolean doFallout = false;
    public boolean doFlood = false;
    public int waterLevel = 0;

    // Rain (column) thresholds, identical to EntityFalloutRain.setScale
    private double s0, s1, s2, s3, s4, s5, s6, s7;
    // Radial (underground) thresholds, identical to EntityFalloutUnderGround.setScale
    private double rS0, rS1, rS2, rS3, rS4, rS5, rS6, rS7;
    private int fallingRadius;
    private int radialRadius;
    private boolean doDrop;

    private final List<Long> chunksToProcess = new ArrayList<>();
    private final List<Long> outerChunksToProcess = new ArrayList<>();

    // ---- parallel state ----
    private ForkJoinPool pool;
    private ConcurrentHashMap<Long, Chunk> mirror;
    private final ConcurrentHashMap<Long, ConcurrentMap<Long, IBlockState>> replacements = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<FallColumn>> fallColumns = new ConcurrentHashMap<>();
    // Positions whose replacement used flag=3 (NOTIFY_NEIGHBORS) in the original, keyed by chunk pos.
    private final ConcurrentHashMap<Long, Set<Long>> notifyPositions = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> chunkLoadQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<double[]>> radialWaitingRoom = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<Long> applyQueue = new PriorityBlockingQueue<>(64, (a, b) ->
            Double.compare(chunkDistSq(a.longValue()), chunkDistSq(b.longValue())));
    private final Long2IntOpenHashMap sectionMaskByChunk = new Long2IntOpenHashMap();
    // Moved light sources recorded during letFall: {x, oldY, z, newY, lightValue}.
    private final List<int[]> movedLights = new ArrayList<>();
    // Chunks that are actually converted (allChunks). retryChunk must NOT call processChunk for the
    // margin chunks outside this set, otherwise pendingChunks is decremented below zero and never
    // reaches 0 (which blocks collectFinished and setDead forever).
    private final Set<Long> conversionChunks = ConcurrentHashMap.newKeySet();
    // Chunks whose column scan has already run. processChunk must decrement pendingChunks only on the
    // first scan: a re-scan triggered by the applyChunk reload must not decrement again (otherwise
    // pendingChunks goes negative and never reaches 0).
    private final Set<Long> scannedChunks = ConcurrentHashMap.newKeySet();
    // Chunks whose terrain has been confirmed populated. isTerrainPopulated persists across unload,
    // so isNeighborhoodPopulated() checks this instead of loadedChunks (a chunk unloaded by the provider must not
    // re-fail the gate and re-offer forever).
    private final Set<Long> populatedChunks = ConcurrentHashMap.newKeySet();
    private volatile Throwable failure;
    private volatile UUID detonator;
    private int jobDimension = Integer.MIN_VALUE;

    private volatile int poolAcquired, jobRegistered, mapAcquired, collectFinished, destroyFinished,
            activeWorkerTasks, pendingChunks, finishQueued, cancelCleanup;
    private volatile boolean cancelling;

    private static final long OFF_POOL_ACQUIRED = UnsafeHolder.fieldOffset(EntityFalloutRainParallelized.class, "poolAcquired");
    private static final long OFF_JOB_REGISTERED = UnsafeHolder.fieldOffset(EntityFalloutRainParallelized.class, "jobRegistered");
    private static final long OFF_MAP_ACQUIRED = UnsafeHolder.fieldOffset(EntityFalloutRainParallelized.class, "mapAcquired");
    private static final long OFF_COLLECT_FINISHED = UnsafeHolder.fieldOffset(EntityFalloutRainParallelized.class, "collectFinished");
    private static final long OFF_DESTROY_FINISHED = UnsafeHolder.fieldOffset(EntityFalloutRainParallelized.class, "destroyFinished");
    private static final long OFF_ACTIVE_WORKER_TASKS = UnsafeHolder.fieldOffset(EntityFalloutRainParallelized.class, "activeWorkerTasks");
    private static final long OFF_PENDING_CHUNKS = UnsafeHolder.fieldOffset(EntityFalloutRainParallelized.class, "pendingChunks");
    private static final long OFF_FINISH_QUEUED = UnsafeHolder.fieldOffset(EntityFalloutRainParallelized.class, "finishQueued");
    private static final long OFF_CANCEL_CLEANUP = UnsafeHolder.fieldOffset(EntityFalloutRainParallelized.class, "cancelCleanup");

    public EntityFalloutRainParallelized(World world) {
        super(world);
        this.setSize(4, 20);
        this.ignoreFrustumCheck = false;
        this.isImmuneToFire = true;
        this.waterLevel = getInt(CompatibilityConfig.fillCraterWithWater.get(world.provider.getDimension()));
        if (this.waterLevel == 0) {
            this.waterLevel = world.getSeaLevel();
        } else if (this.waterLevel < 0 && this.waterLevel > -world.getSeaLevel()) {
            this.waterLevel = world.getSeaLevel() - this.waterLevel;
        }
    }

    public static int getInt(Object e) {
        if (e == null) return 0;
        return (int) e;
    }

    @Override
    protected void entityInit() {
        super.entityInit();
        this.dataManager.register(SCALE, 0);
    }

    public void setScale(int rainScale, int radialRadius, int fallingRadius) {
        this.dataManager.set(SCALE, rainScale);
        this.s0 = 0.8D * rainScale;
        this.s1 = 0.65D * rainScale;
        this.s2 = 0.5D * rainScale;
        this.s3 = 0.4D * rainScale;
        this.s4 = 0.3D * rainScale;
        this.s5 = 0.2D * rainScale;
        this.s6 = 0.1D * rainScale;
        this.s7 = 0.05D * rainScale;

        this.radialRadius = radialRadius;
        this.rS0 = 0.84 * radialRadius;
        this.rS1 = 0.74 * radialRadius;
        this.rS2 = 0.64 * radialRadius;
        this.rS3 = 0.54 * radialRadius;
        this.rS4 = 0.44 * radialRadius;
        this.rS5 = 0.34 * radialRadius;
        this.rS6 = 0.24 * radialRadius;
        this.rS7 = 0.05 * radialRadius;

        this.fallingRadius = fallingRadius > 15 ? fallingRadius : 0;
        this.doDrop = this.fallingRadius > 20;
    }

    public int getScale() {
        int scale = this.dataManager.get(SCALE);
        return scale == 0 ? 1 : scale;
    }

    private void gatherChunks() {
        Set<Long> chunks = new LinkedHashSet<>();
        Set<Long> outerChunks = new LinkedHashSet<>();
        int outerRange = doFallout ? getScale() : fallingRadius;
        int adjustedMaxAngle = 20 * outerRange / 32;
        for (int angle = 0; angle <= adjustedMaxAngle; angle++) {
            double rad = angle * Math.PI / 180.0 / (adjustedMaxAngle / 360.0);
            double dx = outerRange * Math.cos(rad);
            double dz = outerRange * Math.sin(rad);
            outerChunks.add(ChunkPos.asLong((int) (posX + dx) >> 4, (int) (posZ + dz) >> 4));
        }
        for (int distance = 0; distance <= outerRange; distance += 8) {
            for (int angle = 0; angle <= adjustedMaxAngle; angle++) {
                double rad = angle * Math.PI / 180.0 / (adjustedMaxAngle / 360.0);
                double dx = distance * Math.cos(rad);
                double dz = distance * Math.sin(rad);
                long chunkCoord = ChunkPos.asLong((int) (posX + dx) >> 4, (int) (posZ + dz) >> 4);
                if (!outerChunks.contains(chunkCoord)) chunks.add(chunkCoord);
            }
        }
        chunksToProcess.addAll(chunks);
        outerChunksToProcess.addAll(outerChunks);
    }

    @Override
    public void onUpdate() {
        if (world.isRemote) return;
        if (!CompatibilityConfig.isWarDim(world)) {
            setDead();
            return;
        }
        if (failure != null) {
            setDead();
            return;
        }
        if (collectFinished == 0 && mirror == null) {
            startWorkers();
        }
        if (collectFinished == 0 && (ticksExisted & 3) == 0) {
            submitRetry();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BombConfig.mk5);
        while (System.nanoTime() < deadline) {
            Long cp = chunkLoadQueue.poll();
            if (cp != null) {
                loadChunk(cp.longValue());
            }
            Long apply = applyQueue.poll();
            if (apply != null) {
                applyChunk(apply.longValue());
            }
            if (cp == null && apply == null) break;
        }
        maybeFinish();
    }

    private void startWorkers() {
        if (chunksToProcess.isEmpty() && outerChunksToProcess.isEmpty()) gatherChunks();
        if (poolAcquired == 0) {
            if (U.compareAndSetInt(this, OFF_POOL_ACQUIRED, 0, 1)) pool = BombForkJoinPool.acquire();
        }
        registerJobIfNeeded();
        mirror = ChunkUtil.acquireMirrorMap((WorldServer) world);
        U.putIntRelease(this, OFF_MAP_ACQUIRED, 1);
        List<Long> allChunks = new ArrayList<>(chunksToProcess.size() + outerChunksToProcess.size());
        allChunks.addAll(chunksToProcess);
        allChunks.addAll(outerChunksToProcess);

        // Bounding square of the conversion range, shared by the resume pre-fill and the margin enqueue.
        int minCX = Integer.MAX_VALUE, maxCX = Integer.MIN_VALUE;
        int minCZ = Integer.MAX_VALUE, maxCZ = Integer.MIN_VALUE;
        for (long cp : allChunks) {
            int cx = Library.getChunkPosX(cp), cz = Library.getChunkPosZ(cp);
            if (cx < minCX) minCX = cx;
            if (cx > maxCX) maxCX = cx;
            if (cz < minCZ) minCZ = cz;
            if (cz > maxCZ) maxCZ = cz;
        }
        prepareResumeState(allChunks);

        // Enqueue every chunk of the radius + 2-chunk margin through loadChunk so it is populated,
        // mirrored and offered to the workers uniformly. loadChunk skips populate for already-populated
        // chunks (correct), but still mirrors them and adds them to populatedChunks, so both the overlap
        // case and the decoration cascade are handled.
        for (int cx = maxCX + 2; cx >= minCX - 2; cx--) {
            for (int cz = maxCZ + 2; cz >= minCZ - 2; cz--) {
                chunkLoadQueue.offer(ChunkPos.asLong(cx, cz));
            }
        }

        if (pool == null || pool.isShutdown()) {
            U.putIntRelease(this, OFF_COLLECT_FINISHED, 1);
            maybeFinish();
            return;
        }
        int grain = Math.max(1, allChunks.size() / Math.max(1, pool.getParallelism() * 4));
        if (grain < 1) grain = 1;
        // EX order: the radial (underground) phase runs first, then the column (rain) phase.
        ForkJoinTask<?> radialPhase = null;
        if (radialRadius > 0) {
            int rayCount = (int) (Math.PI * radialRadius * radialRadius);
            int rayGrain = Math.max(512, rayCount / Math.max(1, pool.getParallelism() * 4));
            radialPhase = pool.submit(new RadialTask(0, rayCount, rayGrain, rayCount));
        }
        pool.submit(new ColumnTask(allChunks, 0, allChunks.size(), grain, radialPhase));
    }

    /**
     * Seed the resume state:
     * - scannedChunks       <- sectionMaskByChunk (persisted): already-converted chunks are skipped so
     * their sellafield is not re-downgraded.
     * - populatedChunks  <- restored from NBT (resume) + loaded chunks (fresh): the decoration-ran
     * gate consumed by isNeighborhoodPopulated().
     */
    private void prepareResumeState(List<Long> allChunks) {
        for (Chunk c : ((WorldServer) world).getChunkProvider().loadedChunks.values()) {
            if (c.isTerrainPopulated()) populatedChunks.add(ChunkPos.asLong(c.x, c.z));
        }
        int preScanned = 0;
        for (long cp : sectionMaskByChunk.keySet()) {
            if (scannedChunks.add(cp)) preScanned++;
        }
        pendingChunks = allChunks.size() - preScanned;
        conversionChunks.clear();
        conversionChunks.addAll(allChunks);
    }

    private void registerJobIfNeeded() {
        if (U.compareAndSetInt(this, OFF_JOB_REGISTERED, 0, 1)) {
            int dim = world == null ? Integer.MIN_VALUE : world.provider.getDimension();
            jobDimension = dim;
            BombForkJoinPool.register(dim, this);
        }
    }

    private void unregisterJobIfNeeded() {
        if (U.getAndSetInt(this, OFF_JOB_REGISTERED, 0) != 0) {
            int dim = jobDimension;
            jobDimension = Integer.MIN_VALUE;
            if (dim != Integer.MIN_VALUE) BombForkJoinPool.unregister(dim, this);
        }
    }

    private void releasePoolIfHeld() {
        unregisterJobIfNeeded();
        if (U.getAndSetInt(this, OFF_POOL_ACQUIRED, 0) != 0) {
            BombForkJoinPool.release();
            pool = null;
        }
    }

    @ServerThread
    private void loadChunk(long chunkPos) {
        int cx = Library.getChunkPosX(chunkPos);
        int cz = Library.getChunkPosZ(chunkPos);
        Chunk chunk = world.getChunk(cx, cz);
        if (chunk != null) {
            boolean wasPopulated = chunk.isTerrainPopulated();
            if (!wasPopulated) {
                world.getChunk(cx + 1, cz);
                world.getChunk(cx, cz + 1);
                world.getChunk(cx + 1, cz + 1);
                ChunkProviderServer provider = (ChunkProviderServer) world.getChunkProvider();
                chunk.populate(provider, provider.chunkGenerator);
                chunk.generateSkylightMap();
            }
            populatedChunks.add(chunkPos);
            mirror.put(chunkPos, chunk);
        }
        ForkJoinPool p = pool;
        if (p != null && !p.isShutdown()) {
            U.getAndAddInt(this, OFF_ACTIVE_WORKER_TASKS, 1);
            p.submit(() -> {
                try {
                    // EX order on a freshly loaded chunk: resume the radial phase first, then the column phase,
                    // so the column scan sees the radial sellafield (and downgrades it).
                    resumeRadialRays(chunkPos);
                    retryChunk(chunkPos);
                } catch (Exception e) {
                    fail(e);
                } finally {
                    workerFinished();
                }
            });
        }
    }

    private void resumeRadialRays(long chunkPos) {
        ConcurrentLinkedQueue<double[]> waiters = radialWaitingRoom.remove(chunkPos);
        if (waiters == null || waiters.isEmpty()) return;
        RadialWorker worker = new RadialWorker();
        double[] dir;
        while ((dir = waiters.poll()) != null) {
            if (Thread.currentThread().isInterrupted() || destroyFinished != 0) break;
            worker.processRay(dir[0], dir[1], dir[2]);
        }
    }

    private void workerFinished() {
        U.getAndAddInt(this, OFF_ACTIVE_WORKER_TASKS, -1);
    }

    private void maybeFinish() {
        if (collectFinished == 0) {
            if (activeWorkerTasks == 0 && pendingChunks == 0) {
                U.putIntRelease(this, OFF_COLLECT_FINISHED, 1);
            } else {
                return;
            }
        }
        if (destroyFinished != 0) return;
        if (activeWorkerTasks != 0 || pendingChunks != 0) return;
        if (!applyQueue.isEmpty() || !chunkLoadQueue.isEmpty()) return;
        if (!U.compareAndSetInt(this, OFF_FINISH_QUEUED, 0, 1)) return;
        ((WorldServer) world).addScheduledTask(() -> {
            secondPass();
            U.putIntRelease(this, OFF_DESTROY_FINISHED, 1);
            releasePoolIfHeld();
            if (U.getAndSetInt(this, OFF_MAP_ACQUIRED, 0) != 0) {
                ChunkUtil.releaseMirrorMap((WorldServer) world);
            }
            triggerWeather();
            setDead();
        });
    }

    private void triggerWeather() {
        if (RadiationConfig.rain > 0 && doFlood) {
            int scale = getScale();
            if ((doFallout && scale > 160) || scale > 200) {
                world.getWorldInfo().setThundering(true);
                world.getWorldInfo().setThunderTime(RadiationConfig.rain);
                AuxSavedData.setThunder(world, RadiationConfig.rain);
            } else if ((doFallout && scale > 80) || scale > 100) {
                world.getWorldInfo().setRaining(true);
                world.getWorldInfo().setRainTime(RadiationConfig.rain);
            }
        }
    }

    @ServerThread
    private void secondPass() {
        PlayerChunkMap playerChunkMap = ((WorldServer) world).getPlayerChunkMap();
        Long2ObjectMap<Chunk> loaded = ((WorldServer) world).getChunkProvider().loadedChunks;
        ObjectIterator<Long2IntMap.Entry> iterator = sectionMaskByChunk.long2IntEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Long2IntMap.Entry e = iterator.next();
            int changedMask = e.getIntValue();
            if (changedMask == 0) continue;
            long cp = e.getLongKey();
            Chunk chunk = loaded.get(cp);
            if (chunk == null) continue;
            PlayerChunkMapEntry entry = playerChunkMap.getEntry(Library.getChunkPosX(cp), Library.getChunkPosZ(cp));
            if (entry != null) {
                entry.sendPacket(new SPacketChunkData(chunk, changedMask));
            }
        }
        sectionMaskByChunk.clear();
    }

    @ServerThread
    private void applyChunk(long cpLong) {
        ConcurrentMap<Long, IBlockState> changes = replacements.remove(cpLong);
        ConcurrentLinkedQueue<FallColumn> falls = fallColumns.remove(cpLong);
        boolean hasChanges = changes != null && !changes.isEmpty();
        boolean hasFalls = falls != null && !falls.isEmpty();
        if (!hasChanges && !hasFalls) {
            return;
        }
        int cx = Library.getChunkPosX(cpLong);
        int cz = Library.getChunkPosZ(cpLong);
        Chunk chunk = ((WorldServer) world).getChunkProvider().loadedChunks.get(cpLong);
        if (chunk == null) {
            if (hasChanges) replacements.put(cpLong, changes);
            chunkLoadQueue.offer(cpLong);
            return;
        }
        ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
        Int2ObjectOpenHashMap<IBlockState>[] buckets = new Int2ObjectOpenHashMap[16];
        int selfMask = 0;
        Long2ObjectOpenHashMap<IBlockState> oldStates = new Long2ObjectOpenHashMap<>();
        if (hasChanges) {
            for (Map.Entry<Long, IBlockState> e : changes.entrySet()) {
                long packed = e.getKey().longValue();
                int x = Library.getBlockPosX(packed);
                int y = Library.getBlockPosY(packed);
                int z = Library.getBlockPosZ(packed);
                int subY = y >> 4;
                if (subY < 0 || subY >= 16) continue;
                if (buckets[subY] == null) buckets[subY] = new Int2ObjectOpenHashMap<>();
                buckets[subY].put(Library.packLocal(x & 15, y & 15, z & 15), e.getValue());
            }
        }
        for (int subY = 0; subY < 16; subY++) {
            Int2ObjectOpenHashMap<IBlockState> bucket = buckets[subY];
            if (bucket == null || bucket.isEmpty()) continue;
            ExtendedBlockStorage src = storages[subY];
            Optional<ExtendedBlockStorage> changed = ChunkUtil.copyAndModify(cx, cz, subY, world.provider.hasSkyLight(), src, bucket, oldStates);
            if (changed == null) continue;
            ExtendedBlockStorage carved = changed.orElse(Chunk.NULL_BLOCK_STORAGE);
            storages[subY] = carved;
            selfMask |= 1 << subY;
        }
        MutableBlockPos p = new MutableBlockPos();
        for (Long2ObjectMap.Entry<IBlockState> e : oldStates.long2ObjectEntrySet()) {
            IBlockState oldState = e.getValue();
            if (oldState.getBlock().hasTileEntity(oldState)) {
                Library.fromLong(p, e.getLongKey());
                world.removeTileEntity(p);
            }
        }
        // Reproduce the original flag=3 (NOTIFY_NEIGHBORS) side effects of world.setBlockState only for the
        // positions whose replacement used flag=3 in EntityFalloutRain. Passes the OLD block, exactly like
        // setBlockState's notifyNeighborsRespectDebug(pos, oldState.getBlock(), true). Water drain/flood and
        // the flag=2 conversions (placeBlockFromDist, ores, ice, clay, mushroom, etc.) are intentionally left out.
        Set<Long> notify = notifyPositions.remove(cpLong);
        if (notify != null && !notify.isEmpty()) {
            for (long packed : notify) {
                IBlockState oldState = oldStates.get(packed);
                if (oldState == null) continue;
                Library.fromLong(p, packed);
                world.notifyNeighborsOfStateChange(p, oldState.getBlock(), true);
            }
        }
        int lightMask = selfMask;
        // Apply structural collapse (letFall) for columns that were found to have gaps.
        if (hasFalls) {
            FallColumn fc;
            while ((fc = falls.poll()) != null) {
                lightMask |= letFall(fc);
            }
        }
        boolean movedLight = !movedLights.isEmpty();
        if (lightMask != 0) {
            sectionMaskByChunk.put(cpLong, sectionMaskByChunk.get(cpLong) | lightMask);
            // A moved light source leaves stale light that can cross into neighbouring chunks; recompute
            // its old + new light spheres locally (Manhattan/octahedron, radius = its light value).
            if (movedLight) {
                lightMask |= ChunkUtil.blockLightMask(chunk);
                for (int[] src : movedLights) {
                    ChunkUtil.relightLocal(world, src[0], src[1], src[2], src[4]);
                    ChunkUtil.relightLocal(world, src[0], src[3], src[2], src[4]);
                }
                movedLights.clear();
            } else {
                ChunkUtil.relightChunk(chunk);
            }
            chunk.markDirty();
            // Immediately notify the client so it sees the converted blocks without waiting for the final secondPass.
            PlayerChunkMapEntry entry = ((WorldServer) world).getPlayerChunkMap().getEntry(cx, cz);
            if (entry != null) {
                entry.sendPacket(new SPacketChunkData(chunk, lightMask));
            }
        }
    }

    private int letFall(FallColumn fc) {
        int fallChance = RadiationConfig.blocksFallCh;
        if (fallChance < 1) return 0;
        if (fallChance < 100) {
            if (world.rand.nextInt(100) < fallChance) return 0;
        }

        int mask = 0;
        int bottomHeight = fc.lastGapHeight;
        MutableBlockPos pos = new MutableBlockPos();
        for (int y = fc.lastGapHeight; y <= fc.contactHeight; y++) {
            pos.setPos(fc.x, y, fc.z);
            IBlockState state = world.getBlockState(pos);
            Block b = state.getBlock();

            if (b.isReplaceable(world, pos)) continue;

            float hardness = b.getExplosionResistance(null);

            if (hardness > 15) {
                bottomHeight = y + 1;
                continue;
            }

            if (hardness >= 0 && y != bottomHeight) {
                BlockPos target = new BlockPos(fc.x, bottomHeight, fc.z);
                IBlockState targetState = world.getBlockState(target);

                if (targetState.getBlock().isReplaceable(world, target)) {
                    TileEntity te = world.getTileEntity(pos);
                    NBTTagCompound teNBT = null;
                    if (te != null) {
                        teNBT = new NBTTagCompound();
                        te.writeToNBT(teNBT);
                        teNBT.setInteger("x", target.getX());
                        teNBT.setInteger("y", target.getY());
                        teNBT.setInteger("z", target.getZ());
                        world.removeTileEntity(pos);
                    }

                    world.setBlockState(pos, Blocks.AIR.getDefaultState());
                    world.setBlockState(target, state);
                    mask |= 1 << (y >> 4);
                    mask |= 1 << (bottomHeight >> 4);
                    if (b.getLightValue(state) > 0) {
                        movedLights.add(new int[]{pos.getX(), y, pos.getZ(), target.getY(), b.getLightValue(state)});
                    }

                    if (teNBT != null) {
                        TileEntity newTE = world.getTileEntity(target);
                        if (newTE != null) {
                            newTE.readFromNBT(teNBT);
                            newTE.validate();
                            world.markBlockRangeForRenderUpdate(target, target);
                            world.notifyBlockUpdate(target, state, state, 3);
                        }
                    }

                    if (!(b.getCollisionBoundingBox(state, world, pos) == Block.NULL_AABB)) {
                        float distance = y - bottomHeight;
                        int i = MathHelper.ceil(distance - 1.0F);
                        if (i > 0) {
                            float fallHurtAmount = 2.0F * (hardness / 15F);
                            float damage = Math.min(MathHelper.floor((float) i * fallHurtAmount), 40.0F);
                            AxisAlignedBB blockBox = state.getCollisionBoundingBox(world, target);
                            if (blockBox != Block.NULL_AABB) {
                                List<Entity> entities = world.getEntitiesWithinAABB(Entity.class, blockBox.offset(target));
                                for (Entity entity : entities) {
                                    entity.attackEntityFrom(DamageSource.FALLING_BLOCK, damage);
                                }
                            }
                        }
                    }
                }
            }
            bottomHeight++;
        }
        return mask;
    }

    // ===================== worker tasks =====================

    final class ColumnTask extends RecursiveAction {
        final List<Long> chunks;
        final int start, end, threshold;
        final ForkJoinTask<?> radial;

        ColumnTask(List<Long> chunks, int start, int end, int threshold, ForkJoinTask<?> radial) {
            this.chunks = chunks;
            this.start = start;
            this.end = end;
            this.threshold = Math.max(1, threshold);
            this.radial = radial;
        }

        @Override
        protected void compute() {
            if (radial != null) radial.join();
            int len = end - start;
            if (len <= threshold) {
                U.getAndAddInt(EntityFalloutRainParallelized.this, OFF_ACTIVE_WORKER_TASKS, 1);
                try {
                    ColumnWorker worker = new ColumnWorker();
                    for (int i = start; i < end; i++) {
                        if (Thread.currentThread().isInterrupted() || destroyFinished != 0) break;
                        long cp = chunks.get(i).longValue();
                        int cx = Library.getChunkPosX(cp);
                        int cz = Library.getChunkPosZ(cp);
                        Chunk chunk = ChunkUtil.getLoadedChunk(mirror, cp);
                        if (chunk == null || !isNeighborhoodPopulated(cp) || !chunk.isTerrainPopulated()) {
                            // Not ready yet; submitRetry re-scans it via the mirror, no disk re-read.
                            continue;
                        }
                        worker.processChunk(cp, cx, cz, chunk.getBlockStorageArray());
                    }
                } catch (Exception e) {
                    fail(e);
                } finally {
                    workerFinished();
                }
            } else {
                int mid = start + (len >>> 1);
                invokeAll(new ColumnTask(chunks, start, mid, threshold, radial), new ColumnTask(chunks, mid, end, threshold, radial));
            }
        }
    }

    final class RetryTask extends RecursiveAction {
        final long[] chunks;
        final int start, end, threshold;

        RetryTask(long[] chunks, int start, int end, int threshold) {
            this.chunks = chunks;
            this.start = start;
            this.end = end;
            this.threshold = Math.max(1, threshold);
        }

        @Override
        protected void compute() {
            int len = end - start;
            if (len <= threshold) {
                U.getAndAddInt(EntityFalloutRainParallelized.this, OFF_ACTIVE_WORKER_TASKS, 1);
                try {
                    ColumnWorker worker = new ColumnWorker();
                    for (int i = start; i < end; i++) {
                        if (Thread.currentThread().isInterrupted() || destroyFinished != 0) break;
                        long cp = chunks[i];
                        if (scannedChunks.contains(cp)) continue;
                        int cx = Library.getChunkPosX(cp);
                        int cz = Library.getChunkPosZ(cp);
                        Chunk chunk = ChunkUtil.getLoadedChunk(mirror, cp);
                        if (chunk == null || !isNeighborhoodPopulated(cp) || !chunk.isTerrainPopulated()) continue;
                        worker.processChunk(cp, cx, cz, chunk.getBlockStorageArray());
                    }
                } catch (Exception e) {
                    fail(e);
                } finally {
                    workerFinished();
                }
            } else {
                int mid = start + (len >>> 1);
                invokeAll(new RetryTask(chunks, start, mid, threshold), new RetryTask(chunks, mid, end, threshold));
            }
        }
    }

    final class RadialTask extends RecursiveAction {
        final int start, end, threshold, total;

        RadialTask(int start, int end, int threshold, int total) {
            this.start = start;
            this.end = end;
            this.threshold = Math.max(1, threshold);
            this.total = total;
        }

        @Override
        protected void compute() {
            int len = end - start;
            if (len <= threshold) {
                U.getAndAddInt(EntityFalloutRainParallelized.this, OFF_ACTIVE_WORKER_TASKS, 1);
                try {
                    RadialWorker worker = new RadialWorker();
                    for (int i = start; i < end; i++) {
                        if (Thread.currentThread().isInterrupted() || destroyFinished != 0) break;
                        double fy = (2D * i / (total - 1D)) - 1D;
                        double fr = Math.sqrt(1D - fy * fy);
                        double theta = Math.PI * (3.0 - Math.sqrt(5.0)) * i;
                        worker.processRay(Math.cos(theta) * fr, fy, Math.sin(theta) * fr);
                    }
                } catch (Exception e) {
                    fail(e);
                } finally {
                    workerFinished();
                }
            } else {
                int mid = start + (len >>> 1);
                invokeAll(new RadialTask(start, mid, threshold, total), new RadialTask(mid, end, threshold, total));
            }
        }
    }

    /**
     * True once all 8 neighbours are terrain-populated (their decoration has run).
     */
    private boolean isNeighborhoodPopulated(long cp) {
        int cx = Library.getChunkPosX(cp);
        int cz = Library.getChunkPosZ(cp);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (!populatedChunks.contains(ChunkPos.asLong(cx + dx, cz + dz))) return false;
            }
        }
        return true;
    }

    private void retryChunk(long cp) {
        if (!conversionChunks.contains(cp)) {
            return; // margin chunk, loaded only to satisfy the isNeighborhoodPopulated() neighbour gate, not converted
        }
        int cx = Library.getChunkPosX(cp);
        int cz = Library.getChunkPosZ(cp);
        Chunk chunk = ChunkUtil.getLoadedChunk(mirror, cp);
        if (chunk == null || !isNeighborhoodPopulated(cp) || !chunk.isTerrainPopulated()) {
            // Not ready yet (neighbour decoration still pending); submitRetry re-scans it via the mirror, no disk re-read.
            return;
        }
        ColumnWorker worker = new ColumnWorker();
        worker.processChunk(cp, cx, cz, chunk.getBlockStorageArray());
    }

    private void submitRetry() {
        if (destroyFinished != 0 || failure != null) return;
        ForkJoinPool p = pool;
        if (p == null || p.isShutdown()) return;
        List<Long> pending = new ArrayList<>();
        for (long cp : conversionChunks) {
            if (!scannedChunks.contains(cp)) pending.add(cp);
        }
        if (pending.isEmpty()) return;
        long[] arr = new long[pending.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = pending.get(i).longValue();
        int grain = Math.max(1, arr.length / Math.max(1, p.getParallelism() * 4));
        p.submit(new RetryTask(arr, 0, arr.length, grain));
    }

    static final class FallColumn {
        final int x, z, lastGapHeight, contactHeight;

        FallColumn(int x, int z, int lastGapHeight, int contactHeight) {
            this.x = x;
            this.z = z;
            this.lastGapHeight = lastGapHeight;
            this.contactHeight = contactHeight;
        }
    }

    // ===================== column conversion (mirrors EntityFalloutRain) =====================

    final class ColumnWorker {
        private final Random rnd = new Random(ThreadLocalRandom.current().nextLong());

        void processChunk(long cp, int cx, int cz, ExtendedBlockStorage[] ebs) {
            if (scannedChunks.add(cp)) {
                ConcurrentMap<Long, IBlockState> out = replacements.computeIfAbsent(cp, k -> new ConcurrentHashMap<>());
                boolean isOuter = outerChunksToProcess.contains(cp);
                MutableBlockPos pos = new MutableBlockPos();
                int xBase = cx << 4;
                int zBase = cz << 4;
                for (int x = xBase; x < xBase + 16; x++) {
                    for (int z = zBase; z < zBase + 16; z++) {
                        double dist = Math.hypot(x - posX, z - posZ);
                        if (isOuter && dist > getScale()) continue;
                        if (dist > s0) {
                            if (rnd.nextFloat() > 0.05F + (5F * (s0 / dist) - 4F)) continue;
                        }
                        pos.setPos(x, 0, z);
                        FallColumn fc = doColumn(pos, dist, ebs, out, x, z);
                        if (fc != null) {
                            fallColumns.computeIfAbsent(cp, k -> new ConcurrentLinkedQueue<>()).offer(fc);
                        }
                    }
                }
                U.getAndAddInt(EntityFalloutRainParallelized.this, OFF_PENDING_CHUNKS, -1);
                // Non-nuclear bombs (doFallout=false) produce no block replacements but can still have
                // gap columns that must collapse (letFall). Offer to the apply queue in that case too.
                boolean hasFalls = fallColumns.containsKey(cp);
                if (out.isEmpty() && !hasFalls) {
                    replacements.remove(cp);
                } else {
                    applyQueue.offer(cp);
                }
            } else {
                // Re-scan after an unload-reoffer: the replacements were put back by applyChunk, but the
                // column scan already ran. Re-queue them so they get applied without re-scanning.
                ConcurrentMap<Long, IBlockState> existing = replacements.get(cp);
                if (existing != null && !existing.isEmpty()) {
                    applyQueue.offer(cp);
                }
            }
        }

        FallColumn doColumn(MutableBlockPos pos, double dist, ExtendedBlockStorage[] ebs, ConcurrentMap<Long, IBlockState> out, int x, int z) {
            int stoneDepth = 0;
            int maxStoneDepth = doFallout ? getMaxStoneDepth(dist) : 6;
            boolean reachedStone = false;
            boolean lastReachedStone = false;
            int contactHeight = 420;
            int lastGapHeight = 420;
            boolean gapFound = false;
            boolean[] stop = new boolean[]{false};

            for (int y = 255; y >= 0; y--) {
                IBlockState b = getEffectiveState(x, y, z, ebs);
                Block bblock = b.getBlock();
                Material bmaterial = b.getMaterial();

                lastReachedStone = reachedStone;

                if (contactHeight == 420 && (doFallout ? bblock != Blocks.AIR : bblock.isCollidable()))
                    contactHeight = Math.min(y + 1, 255);

                if (reachedStone && bmaterial != Material.AIR) stoneDepth++;
                else reachedStone = bmaterial == Material.ROCK;
                if (reachedStone && stoneDepth > maxStoneDepth) break;

                if (bmaterial == Material.AIR || bmaterial.isLiquid()) {
                    if (y < contactHeight) {
                        gapFound = true;
                        lastGapHeight = y;
                    }
                    continue;
                }

                if (doFallout) {
                    stop[0] = false;
                    convertColumnBlock(x, y, z, dist, b, bblock, bmaterial, reachedStone, lastReachedStone, stoneDepth, maxStoneDepth, out, ebs, contactHeight, stop);
                    if (stop[0]) break;
                }
            }

            if (dist < fallingRadius) {
                if (doFlood) floodColumn(x, z, out, ebs);
                else drainColumn(x, z, out, ebs);
            }

            if (doDrop && gapFound && dist < fallingRadius) {
                return new FallColumn(x, z, lastGapHeight, contactHeight);
            }
            return null;
        }
    }

    private int getMaxStoneDepth(double dist) {
        if (dist > s1) return 0;
        else if (dist > s2) return 1;
        else if (dist > s3) return 2;
        else if (dist > s4) return 3;
        else if (dist > s5) return 4;
        else if (dist > s6) return 5;
        else if (dist <= s6) return 6;
        return 7;
    }

    private static final Block[] SELLAFIELD = {
            ModBlocks.sellafield_slaked, ModBlocks.sellafield_0, ModBlocks.sellafield_1,
            ModBlocks.sellafield_2, ModBlocks.sellafield_3, ModBlocks.sellafield_4, ModBlocks.sellafield_core
    };

    private static final Set<Block> META_TIERED = new HashSet<>(Arrays.asList(
            ModBlocks.fallout, ModBlocks.waste_earth, ModBlocks.waste_dirt,
            ModBlocks.waste_gravel, ModBlocks.waste_snow, ModBlocks.waste_snow_block,
            ModBlocks.waste_mycelium, ModBlocks.waste_sand, ModBlocks.waste_sand_red,
            ModBlocks.waste_trinitite, ModBlocks.waste_trinitite_red,
            ModBlocks.waste_sandstone, ModBlocks.waste_sandstone_red,
            ModBlocks.waste_terracotta, ModBlocks.waste_grass_tall));

    /** Maps a distance to a 0..6 tier index. radial=true uses the rS thresholds; sellafield=true uses the 0.1 jitter (meta uses 0.2); stoneDepth=0 with maxStoneDepth=-1 disables the depth term. */
    private int tierIndex(double dist, Random rnd, boolean radial, boolean sellafield, int stoneDepth, int maxStoneDepth) {
        double s1 = radial ? rS1 : this.s1;
        double s2 = radial ? rS2 : this.s2;
        double s3 = radial ? rS3 : this.s3;
        double s4 = radial ? rS4 : this.s4;
        double s5 = radial ? rS5 : this.s5;
        double s6 = radial ? rS6 : this.s6;
        double spread = sellafield ? 0.1D : 0.2D;
        double ranDist = dist * (1D + rnd.nextDouble() * spread);
        if (ranDist > s1 || stoneDepth == maxStoneDepth) return 0;
        if (ranDist > s2 || stoneDepth == maxStoneDepth - 1) return 1;
        if (ranDist > s3 || stoneDepth == maxStoneDepth - 2) return 2;
        if (ranDist > s4 || stoneDepth == maxStoneDepth - 3) return 3;
        if (ranDist > s5 || stoneDepth == maxStoneDepth - 4) return 4;
        if (ranDist > s6 || stoneDepth == maxStoneDepth - 5) return 5;
        return 6;
    }

    /** Records that this replaced block used flag=3 in the original, so applyChunk must notify its neighbours. */
    /**
     * Squared distance from the epicenter to the given chunk's center, for radius-ordered applying.
     */
    private double chunkDistSq(long cp) {
        int cx = Library.getChunkPosX(cp);
        int cz = Library.getChunkPosZ(cp);
        double dx = (cx << 4) + 8.0 - posX;
        double dz = (cz << 4) + 8.0 - posZ;
        return dx * dx + dz * dz;
    }

    private void markNotify(int x, int y, int z) {
        notifyPositions.computeIfAbsent(ChunkPos.asLong(x >> 4, z >> 4), k -> ConcurrentHashMap.newKeySet())
                .add(Library.blockPosToLong(x, y, z));
    }

    // Applies the EntityFalloutRain.doFallout conversion rules, recording replacements instead of calling setBlockState.
    private void convertColumnBlock(int x, int y, int z, double dist, IBlockState b, Block bblock, Material bmaterial,
                                    boolean reachedStone, boolean lastReachedStone, int stoneDepth, int maxStoneDepth,
                                    ConcurrentMap<Long, IBlockState> out, ExtendedBlockStorage[] ebs, int contactHeight, boolean[] stop) {
        Random rnd = ThreadLocalRandom.current();
        if (bblock == Blocks.BEDROCK || bblock == ModBlocks.ore_bedrock_oil || bblock == ModBlocks.ore_bedrock_block) {
            if (getEffectiveState(x, y + 1, z, ebs).getBlock() == Blocks.AIR) {
                out.put(Library.blockPosToLong(x, y + 1, z), ModBlocks.toxic_block.getDefaultState());
                markNotify(x, y + 1, z);
            }
            stop[0] = true;
            return;
        }

        if (y == contactHeight - 1 && bblock != ModBlocks.fallout && Math.abs(rnd.nextGaussian() * (dist * dist) / (s0 * s0)) < 0.05
                && rnd.nextDouble() < 0.05 && canPlaceFallout(b)) {
            placeBlockFromDist(dist, ModBlocks.fallout, x, y + 1, z, out, rnd);
        }

        if (bblock == ModBlocks.waste_leaves) {
            if (!(dist > s1 || (dist > fallingRadius && (rnd.nextFloat() < (-5F * (fallingRadius / dist) + 5F))))) {
                out.put(Library.blockPosToLong(x, y, z), Blocks.AIR.getDefaultState());
                markNotify(x, y, z);
            }
            return;
        }

        if (bblock instanceof BlockLeaves && !(bblock instanceof WasteLeaves)) {
            if (dist > s1 || (dist > fallingRadius && (rnd.nextFloat() < (-5F * (fallingRadius / dist) + 5F)))) {
                BlockPlanks.EnumType type = null;
                try {
                    type = ((BlockLeaves) bblock).getWoodType(bblock.getMetaFromState(b));
                } catch (UnsupportedOperationException ignored) {
                }
                if (type == null) type = BlockPlanks.EnumType.OAK;
                out.put(Library.blockPosToLong(x, y, z), ModBlocks.waste_leaves.getDefaultState().withProperty(WasteLeaves.VARIANT, type));
                markNotify(x, y, z);
            } else {
                out.put(Library.blockPosToLong(x, y, z), Blocks.AIR.getDefaultState());
                markNotify(x, y, z);
            }
            return;
        }

        if (bblock == Blocks.BROWN_MUSHROOM || bblock == Blocks.RED_MUSHROOM) {
            if (dist < s0) out.put(Library.blockPosToLong(x, y, z), ModBlocks.mush.getDefaultState());
            return;
        }

        if (bblock instanceof BlockOre && reachedStone && !lastReachedStone && dist < s1) {
            out.put(Library.blockPosToLong(x, y, z), ModBlocks.toxic_block.getDefaultState());
            markNotify(x, y, z);
            return;
        }

        if (bblock instanceof BlockStone || bblock == Blocks.COBBLESTONE) {
            out.put(Library.blockPosToLong(x, y, z), SELLAFIELD[tierIndex(dist, rnd, false, true, stoneDepth, maxStoneDepth)].getStateFromMeta(rnd.nextInt(4)));
            markNotify(x, y, z);
            return;
        }

        if (bblock instanceof BlockGrass) {
            placeBlockFromDist(dist, ModBlocks.waste_earth, x, y, z, out, rnd);
            return;
        }
        if (bblock instanceof BlockGravel) {
            placeBlockFromDist(dist, ModBlocks.waste_gravel, x, y, z, out, rnd);
            return;
        }
        if (bblock instanceof BlockDirt) {
            BlockDirt.DirtType meta = b.getValue(BlockDirt.VARIANT);
            if (meta == BlockDirt.DirtType.DIRT) placeBlockFromDist(dist, ModBlocks.waste_dirt, x, y, z, out, rnd);
            else if (meta == BlockDirt.DirtType.COARSE_DIRT)
                placeBlockFromDist(dist, ModBlocks.waste_gravel, x, y, z, out, rnd);
            else if (meta == BlockDirt.DirtType.PODZOL)
                placeBlockFromDist(dist, ModBlocks.waste_mycelium, x, y, z, out, rnd);
            return;
        }
        if (bblock == Blocks.FARMLAND) {
            placeBlockFromDist(dist, ModBlocks.waste_dirt, x, y, z, out, rnd);
            return;
        }
        if (bblock instanceof BlockSnow) {
            placeBlockFromDist(dist, ModBlocks.waste_snow, x, y, z, out, rnd);
            return;
        }
        if (bblock instanceof BlockSnowBlock) {
            placeBlockFromDist(dist, ModBlocks.waste_snow_block, x, y, z, out, rnd);
            return;
        }
        if (bblock instanceof BlockIce) {
            out.put(Library.blockPosToLong(x, y, z), ModBlocks.waste_ice.getDefaultState());
            return;
        }
        if (bblock instanceof BlockBush) {
            IBlockState d = getState(ebs, x, y - 1, z);
            Block dblock = d.getBlock();
            boolean canStay = dblock.canSustainPlant(d, world, new BlockPos(x, y - 1, z), EnumFacing.UP, (IPlantable) bblock);
            if (!canStay || bblock instanceof BlockLilyPad) {
                out.put(Library.blockPosToLong(x, y, z), Blocks.AIR.getDefaultState());
                markNotify(x, y, z);
                return;
            }
            if (dblock == Blocks.FARMLAND) {
                placeBlockFromDist(dist, ModBlocks.waste_dirt, x, y - 1, z, out, rnd);
                placeBlockFromDist(dist, ModBlocks.waste_grass_tall, x, y, z, out, rnd);
                markNotify(x, y, z);
            } else if (dblock instanceof BlockGrass) {
                placeBlockFromDist(dist, ModBlocks.waste_earth, x, y - 1, z, out, rnd);
                placeBlockFromDist(dist, ModBlocks.waste_grass_tall, x, y, z, out, rnd);
                markNotify(x, y, z);
            } else if (dblock instanceof BlockDirt) {
                BlockDirt.DirtType meta = d.getValue(BlockDirt.VARIANT);
                placeBlockFromDist(dist, meta == BlockDirt.DirtType.PODZOL ? ModBlocks.waste_mycelium : ModBlocks.waste_dirt, x, y - 1, z, out, rnd);
                placeBlockFromDist(dist, ModBlocks.waste_grass_tall, x, y, z, out, rnd);
                markNotify(x, y, z);
            } else if (dblock == Blocks.MYCELIUM) {
                placeBlockFromDist(dist, ModBlocks.waste_mycelium, x, y - 1, z, out, rnd);
                out.put(Library.blockPosToLong(x, y, z), ModBlocks.mush.getDefaultState());
                markNotify(x, y, z);
            }
            return;
        }
        if (bblock instanceof BlockReed) {
            out.put(Library.blockPosToLong(x, y, z), Blocks.AIR.getDefaultState());
            markNotify(x, y, z);
            return;
        }
        if (bblock == Blocks.MYCELIUM) {
            placeBlockFromDist(dist, ModBlocks.waste_mycelium, x, y, z, out, rnd);
            return;
        }
        if (bblock == Blocks.SANDSTONE) {
            placeBlockFromDist(dist, ModBlocks.waste_sandstone, x, y, z, out, rnd);
            return;
        }
        if (bblock == Blocks.RED_SANDSTONE) {
            placeBlockFromDist(dist, ModBlocks.waste_sandstone_red, x, y, z, out, rnd);
            return;
        }
        if (bblock == Blocks.HARDENED_CLAY || bblock == Blocks.STAINED_HARDENED_CLAY) {
            placeBlockFromDist(dist, ModBlocks.waste_terracotta, x, y, z, out, rnd);
            return;
        }
        if (bblock instanceof BlockSand) {
            BlockSand.EnumType meta = b.getValue(BlockSand.VARIANT);
            if (rnd.nextInt(60) == 0) {
                placeBlockFromDist(dist, meta == BlockSand.EnumType.SAND ? ModBlocks.waste_trinitite : ModBlocks.waste_trinitite_red, x, y, z, out, rnd);
            } else {
                placeBlockFromDist(dist, meta == BlockSand.EnumType.SAND ? ModBlocks.waste_sand : ModBlocks.waste_sand_red, x, y, z, out, rnd);
            }
            return;
        }
        if (bblock == Blocks.CLAY) {
            out.put(Library.blockPosToLong(x, y, z), Blocks.HARDENED_CLAY.getDefaultState());
            return;
        }
        if (bblock == Blocks.MOSSY_COBBLESTONE) {
            out.put(Library.blockPosToLong(x, y, z), Blocks.COAL_ORE.getDefaultState());
            return;
        }
        if (bblock == Blocks.COAL_ORE || matchesOre(bblock, "oreCoal")) {
            if (dist < s5) {
                int ra = rnd.nextInt(150);
                if (ra < 7) out.put(Library.blockPosToLong(x, y, z), Blocks.DIAMOND_ORE.getDefaultState());
                else if (ra < 10) out.put(Library.blockPosToLong(x, y, z), Blocks.EMERALD_ORE.getDefaultState());
            }
            return;
        }
        if (bblock == ModBlocks.ore_lignite || matchesOre(bblock, "oreLignite")) {
            if (dist < s5) {
                if (rnd.nextInt(150) < 7) out.put(Library.blockPosToLong(x, y, z), Blocks.DIAMOND_ORE.getDefaultState());
            }
            return;
        }
        if (bblock == ModBlocks.ore_beryllium || matchesOre(bblock, "oreBeryllium")) {
            if (dist < s5) {
                if (rnd.nextInt(150) < 10) out.put(Library.blockPosToLong(x, y, z), Blocks.EMERALD_ORE.getDefaultState());
            }
            return;
        }
        if (bblock == Blocks.BROWN_MUSHROOM_BLOCK || bblock == Blocks.RED_MUSHROOM_BLOCK) {
            if (dist < s0) {
                BlockHugeMushroom.EnumType meta = b.getValue(BlockHugeMushroom.VARIANT);
                out.put(Library.blockPosToLong(x, y, z), meta == BlockHugeMushroom.EnumType.STEM
                        ? ModBlocks.mush_block_stem.getDefaultState() : ModBlocks.mush_block.getDefaultState());
            }
            return;
        }
        if (bblock instanceof BlockLog) {
            if (dist < s1) {
                out.put(Library.blockPosToLong(x, y, z), ((WasteLog) ModBlocks.waste_log).getSameRotationState(b));
                markNotify(x, y, z);
            }
            return;
        }
        if (bblock == Blocks.SPONGE) {
            if (b.getValue(BlockSponge.WET)) out.put(Library.blockPosToLong(x, y, z), Blocks.SPONGE.getDefaultState());
            return;
        }
        if (bmaterial == Material.WOOD && bblock != ModBlocks.waste_log && bblock != ModBlocks.waste_planks) {
            if (dist < s1) out.put(Library.blockPosToLong(x, y, z), ModBlocks.waste_planks.getDefaultState());
            return;
        }
        if (bblock == ModBlocks.sellafield_slaked || bblock == ModBlocks.sellafield_0 || bblock == ModBlocks.sellafield_1 || bblock == ModBlocks.sellafield_2 || bblock == ModBlocks.sellafield_3 || bblock == ModBlocks.sellafield_4 || bblock == ModBlocks.sellafield_core) {
            int nt = tierIndex(dist, rnd, false, true, stoneDepth, maxStoneDepth);
            if (nt > Arrays.asList(SELLAFIELD).indexOf(bblock)) {
                out.put(Library.blockPosToLong(x, y, z), SELLAFIELD[nt].getStateFromMeta(rnd.nextInt(4)));
                markNotify(x, y, z);
            }
            return;
        }
        if (META_TIERED.contains(bblock)) {
            int m = tierIndex(dist, rnd, false, false, 0, -1);
            if (m > b.getBlock().getMetaFromState(b)) {
                out.put(Library.blockPosToLong(x, y, z), bblock.getStateFromMeta(m));
                markNotify(x, y, z);
            }
            return;
        }
        if (bblock == Blocks.VINE) {
            out.put(Library.blockPosToLong(x, y, z), Blocks.AIR.getDefaultState());
            markNotify(x, y, z);
            return;
        }
        if (bblock == ModBlocks.ore_uranium || matchesOre(bblock, "oreUranium")) {
            if (dist <= s5) {
                if (rnd.nextInt(VersatileConfig.getSchrabOreChance()) == 0 || dist < s7)
                    out.put(Library.blockPosToLong(x, y, z), ModBlocks.ore_schrabidium.getDefaultState());
                else
                    out.put(Library.blockPosToLong(x, y, z), ModBlocks.ore_uranium_scorched.getDefaultState());
            }
            stop[0] = true;
            return;
        }
        if (bblock == ModBlocks.ore_nether_uranium || matchesOre(bblock, "oreNetherUranium")) {
            if (dist <= s5) {
                if (rnd.nextInt(VersatileConfig.getSchrabOreChance()) == 0)
                    out.put(Library.blockPosToLong(x, y, z), ModBlocks.ore_nether_schrabidium.getDefaultState());
                else
                    out.put(Library.blockPosToLong(x, y, z), ModBlocks.ore_nether_uranium_scorched.getDefaultState());
            }
            stop[0] = true;
            return;
        }
        if (bblock == ModBlocks.ore_gneiss_uranium || matchesOre(bblock, "oreNetherUranium")) {
            if (dist <= s4) {
                if (rnd.nextInt(VersatileConfig.getSchrabOreChance()) == 0)
                    out.put(Library.blockPosToLong(x, y, z), ModBlocks.ore_gneiss_schrabidium.getDefaultState());
                else
                    out.put(Library.blockPosToLong(x, y, z), ModBlocks.ore_gneiss_uranium_scorched.getDefaultState());
            }
            stop[0] = true;
            return;
        }
        if (bblock == ModBlocks.brick_concrete) {
            if (rnd.nextInt(80) == 0)
                out.put(Library.blockPosToLong(x, y, z), ModBlocks.brick_concrete_broken.getDefaultState());
            stop[0] = true;
            return;
        }
        if (bblock.getExplosionResistance(null) > 300) {
            stop[0] = true;
            return;
        }
    }

    private void placeBlockFromDist(double dist, Block b, int x, int y, int z, ConcurrentMap<Long, IBlockState> out, Random rnd) {
        out.put(Library.blockPosToLong(x, y, z), b.getStateFromMeta(tierIndex(dist, rnd, false, false, 0, -1)));
    }

    private void floodColumn(int x, int z, ConcurrentMap<Long, IBlockState> out, ExtendedBlockStorage[] ebs) {
        if (CompatibilityConfig.doFillCraterWithWater && waterLevel > 1) {
            for (int y = waterLevel - 1; y > 1; y--) {
                IBlockState s = getState(ebs, x, y, z);
                Block b = s.getBlock();
                if (s.getMaterial() == Material.AIR || b == Blocks.FLOWING_WATER) {
                    out.put(Library.blockPosToLong(x, y, z), Blocks.WATER.getDefaultState());
                } else if (b.getExplosionResistance(null) > 600_000) {
                    return;
                }
            }
        }
    }

    private void drainColumn(int x, int z, ConcurrentMap<Long, IBlockState> out, ExtendedBlockStorage[] ebs) {
        for (int y = 255; y > 1; y--) {
            Block b = getState(ebs, x, y, z).getBlock();
            if (b == Blocks.WATER || b == Blocks.FLOWING_WATER) {
                out.put(Library.blockPosToLong(x, y, z), Blocks.AIR.getDefaultState());
            }
        }
    }

    // ===================== radial conversion (mirrors EntityFalloutUnderGround) =====================

    final class RadialWorker {
        private final Random rnd = new Random(ThreadLocalRandom.current().nextLong());

        void processRay(double dx, double dy, double dz) {
            for (int l = 0; l < radialRadius; l++) {
                int x = (int) Math.floor(posX + dx * l);
                int y = (int) Math.floor(posY + dy * l);
                int z = (int) Math.floor(posZ + dz * l);
                if (y < 0 || y > 255) return;
                long cp = ChunkPos.asLong(x >> 4, z >> 4);
                ExtendedBlockStorage[] ebs = ChunkUtil.getLoadedEBS(mirror, cp);
                if (ebs == null) {
                    ConcurrentLinkedQueue<double[]> q = radialWaitingRoom.get(cp);
                    if (q == null) {
                        ConcurrentLinkedQueue<double[]> created = new ConcurrentLinkedQueue<>();
                        ConcurrentLinkedQueue<double[]> prev = radialWaitingRoom.putIfAbsent(cp, created);
                        q = (prev != null) ? prev : created;
                        if (prev == null) chunkLoadQueue.offer(cp);
                    }
                    q.offer(new double[]{dx, dy, dz});
                    return;
                }
                IBlockState b = getState(ebs, x, y, z);
                Block bblock = b.getBlock();
                if (bblock == Blocks.AIR) continue;

                ConcurrentMap<Long, IBlockState> out = replacements.computeIfAbsent(cp, k -> new ConcurrentHashMap<>());
                if (bblock instanceof BlockStone || bblock == Blocks.COBBLESTONE) {
                    out.put(Library.blockPosToLong(x, y, z), SELLAFIELD[tierIndex(l, rnd, true, true, 0, -1)].getStateFromMeta(rnd.nextInt(4)));
                    markNotify(x, y, z);
                    return;
                }
                if (bblock == ModBlocks.sellafield_slaked || bblock == ModBlocks.sellafield_0 || bblock == ModBlocks.sellafield_1 || bblock == ModBlocks.sellafield_2 || bblock == ModBlocks.sellafield_3 || bblock == ModBlocks.sellafield_4 || bblock == ModBlocks.sellafield_core) {
                    int nt = tierIndex(l, rnd, true, true, 0, -1);
                    if (nt > Arrays.asList(SELLAFIELD).indexOf(bblock)) {
                        out.put(Library.blockPosToLong(x, y, z), SELLAFIELD[nt].getStateFromMeta(rnd.nextInt(4)));
                        markNotify(x, y, z);
                    }
                    return;
                }
                if (META_TIERED.contains(bblock)) {
                    int m = tierIndex(l, rnd, true, false, 0, -1);
                    if (m > b.getBlock().getMetaFromState(b)) {
                        out.put(Library.blockPosToLong(x, y, z), bblock.getStateFromMeta(m));
                        markNotify(x, y, z);
                    }
                    return;
                }
                if (bblock == Blocks.BEDROCK || bblock == ModBlocks.ore_bedrock_oil || bblock == ModBlocks.ore_bedrock_block) {
                    if (getState(ebs, x, y + 1, z).getBlock() == Blocks.AIR) {
                        out.put(Library.blockPosToLong(x, y + 1, z), ModBlocks.toxic_block.getDefaultState());
                        markNotify(x, y + 1, z);
                    }
                    return;
                }
                if (bblock instanceof BlockLeaves && !(bblock instanceof WasteLeaves)) {
                    if (l > rS1) {
                        BlockPlanks.EnumType type = null;
                        try {
                            type = ((BlockLeaves) bblock).getWoodType(bblock.getMetaFromState(b));
                        } catch (UnsupportedOperationException ignored) {
                        }
                        if (type == null) type = BlockPlanks.EnumType.OAK;
                        out.put(Library.blockPosToLong(x, y, z), ModBlocks.waste_leaves.getDefaultState().withProperty(WasteLeaves.VARIANT, type));
                    } else {
                        out.put(Library.blockPosToLong(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    markNotify(x, y, z);
                    continue;
                }
                if (bblock instanceof BlockBush) {
                    IBlockState d = getState(ebs, x, y - 1, z);
                    Block dblock = d.getBlock();
                    boolean canStay = dblock.canSustainPlant(d, world, new BlockPos(x, y - 1, z), EnumFacing.UP, (IPlantable) bblock);
                    if (!canStay || bblock instanceof BlockLilyPad) {
                        out.put(Library.blockPosToLong(x, y, z), Blocks.AIR.getDefaultState());
                        markNotify(x, y, z);
                        continue;
                    }
                    if (dblock == Blocks.FARMLAND) {
                        placeBlockFromDistRadial(l, ModBlocks.waste_dirt, x, y - 1, z, out, rnd);
                        placeBlockFromDistRadial(l, ModBlocks.waste_grass_tall, x, y, z, out, rnd);
                    } else if (dblock instanceof BlockGrass) {
                        placeBlockFromDistRadial(l, ModBlocks.waste_earth, x, y - 1, z, out, rnd);
                        placeBlockFromDistRadial(l, ModBlocks.waste_grass_tall, x, y, z, out, rnd);
                    } else if (dblock instanceof BlockDirt) {
                        BlockDirt.DirtType meta = d.getValue(BlockDirt.VARIANT);
                        placeBlockFromDistRadial(l, meta == BlockDirt.DirtType.PODZOL ? ModBlocks.waste_mycelium : ModBlocks.waste_dirt, x, y - 1, z, out, rnd);
                        placeBlockFromDistRadial(l, ModBlocks.waste_grass_tall, x, y, z, out, rnd);
                    } else if (dblock == Blocks.MYCELIUM) {
                        placeBlockFromDistRadial(l, ModBlocks.waste_mycelium, x, y - 1, z, out, rnd);
                        out.put(Library.blockPosToLong(x, y, z), ModBlocks.mush.getDefaultState());
                        markNotify(x, y, z);
                    }
                    continue;
                }
                if (bblock instanceof BlockCactus) {
                    out.put(Library.blockPosToLong(x, y, z), Blocks.AIR.getDefaultState());
                    markNotify(x, y, z);
                    continue;
                }
                if (bblock instanceof BlockReed) {
                    out.put(Library.blockPosToLong(x, y, z), Blocks.AIR.getDefaultState());
                    markNotify(x, y, z);
                    continue;
                }
                if (bblock instanceof BlockGrass) {
                    placeBlockFromDistRadial(l, ModBlocks.waste_earth, x, y, z, out, rnd);
                    return;
                }
                if (bblock instanceof BlockDirt) {
                    BlockDirt.DirtType meta = b.getValue(BlockDirt.VARIANT);
                    if (meta == BlockDirt.DirtType.DIRT)
                        placeBlockFromDistRadial(l, ModBlocks.waste_dirt, x, y, z, out, rnd);
                    else if (meta == BlockDirt.DirtType.COARSE_DIRT)
                        placeBlockFromDistRadial(l, ModBlocks.waste_gravel, x, y, z, out, rnd);
                    else if (meta == BlockDirt.DirtType.PODZOL)
                        placeBlockFromDistRadial(l, ModBlocks.waste_mycelium, x, y, z, out, rnd);
                    return;
                }
                if (bblock == Blocks.FARMLAND) {
                    placeBlockFromDistRadial(l, ModBlocks.waste_dirt, x, y, z, out, rnd);
                    continue;
                }
                if (bblock instanceof BlockSnow) {
                    placeBlockFromDistRadial(l, ModBlocks.waste_snow, x, y, z, out, rnd);
                    continue;
                }
                if (bblock instanceof BlockSnowBlock) {
                    placeBlockFromDistRadial(l, ModBlocks.waste_snow_block, x, y, z, out, rnd);
                    continue;
                }
                if (bblock instanceof BlockIce) {
                    out.put(Library.blockPosToLong(x, y, z), ModBlocks.waste_ice.getDefaultState());
                    markNotify(x, y, z);
                    continue;
                }
                if (bblock == Blocks.MYCELIUM) {
                    placeBlockFromDistRadial(l, ModBlocks.waste_mycelium, x, y, z, out, rnd);
                    return;
                }
                if (bblock instanceof BlockGravel) {
                    placeBlockFromDistRadial(l, ModBlocks.waste_gravel, x, y, z, out, rnd);
                    return;
                }
                if (bblock == Blocks.SANDSTONE) {
                    placeBlockFromDistRadial(l, ModBlocks.waste_sandstone, x, y, z, out, rnd);
                    return;
                }
                if (bblock == Blocks.RED_SANDSTONE) {
                    placeBlockFromDistRadial(l, ModBlocks.waste_sandstone_red, x, y, z, out, rnd);
                    return;
                }
                if (bblock == Blocks.HARDENED_CLAY || bblock == Blocks.STAINED_HARDENED_CLAY) {
                    placeBlockFromDistRadial(l, ModBlocks.waste_terracotta, x, y, z, out, rnd);
                    return;
                }
                if (bblock instanceof BlockSand) {
                    BlockSand.EnumType meta = b.getValue(BlockSand.VARIANT);
                    if (rnd.nextInt(60) == 0) {
                        placeBlockFromDistRadial(l, meta == BlockSand.EnumType.SAND ? ModBlocks.waste_trinitite : ModBlocks.waste_trinitite_red, x, y, z, out, rnd);
                    } else {
                        placeBlockFromDistRadial(l, meta == BlockSand.EnumType.SAND ? ModBlocks.waste_sand : ModBlocks.waste_sand_red, x, y, z, out, rnd);
                    }
                    return;
                }
                if (bblock == Blocks.CLAY) {
                    out.put(Library.blockPosToLong(x, y, z), Blocks.HARDENED_CLAY.getDefaultState());
                    markNotify(x, y, z);
                    return;
                }
                if (bblock == Blocks.MOSSY_COBBLESTONE) {
                    out.put(Library.blockPosToLong(x, y, z), Blocks.COAL_ORE.getDefaultState());
                    markNotify(x, y, z);
                    return;
                }
                if (bblock == Blocks.COAL_ORE || matchesOre(bblock, "oreCoal")) {
                    if (l < rS6) {
                        int ra = rnd.nextInt(150);
                        if (ra < 7) {
                            out.put(Library.blockPosToLong(x, y, z), Blocks.DIAMOND_ORE.getDefaultState());
                            markNotify(x, y, z);
                        } else if (ra < 10) {
                            out.put(Library.blockPosToLong(x, y, z), Blocks.EMERALD_ORE.getDefaultState());
                            markNotify(x, y, z);
                        }
                    }
                    return;
                }
                if (bblock == ModBlocks.ore_lignite || matchesOre(bblock, "oreLignite")) {
                    if (l < rS6) {
                        if (rnd.nextInt(150) < 7) {
                            out.put(Library.blockPosToLong(x, y, z), Blocks.DIAMOND_ORE.getDefaultState());
                            markNotify(x, y, z);
                        }
                    }
                    return;
                }
                if (bblock == ModBlocks.ore_beryllium || matchesOre(bblock, "oreBeryllium")) {
                    if (l < rS6) {
                        if (rnd.nextInt(150) < 10) {
                            out.put(Library.blockPosToLong(x, y, z), Blocks.EMERALD_ORE.getDefaultState());
                            markNotify(x, y, z);
                        }
                    }
                    return;
                }
                if (bblock == Blocks.BROWN_MUSHROOM_BLOCK || bblock == Blocks.RED_MUSHROOM_BLOCK) {
                    if (l < rS0) {
                        BlockHugeMushroom.EnumType meta = b.getValue(BlockHugeMushroom.VARIANT);
                        out.put(Library.blockPosToLong(x, y, z), meta == BlockHugeMushroom.EnumType.STEM
                                ? ModBlocks.mush_block_stem.getDefaultState() : ModBlocks.mush_block.getDefaultState());
                        markNotify(x, y, z);
                    }
                    return;
                }
                if (bblock instanceof BlockLog) {
                    if (l < rS0) {
                        out.put(Library.blockPosToLong(x, y, z), ((WasteLog) ModBlocks.waste_log).getSameRotationState(b));
                        markNotify(x, y, z);
                    }
                    return;
                }
                if (bblock instanceof BlockSponge) {
                    if (b.getValue(BlockSponge.WET))
                        out.put(Library.blockPosToLong(x, y, z), Blocks.SPONGE.getDefaultState());
                    return;
                }
                if (bmaterialOf(b) == Material.WOOD && bblock != ModBlocks.waste_log && bblock != ModBlocks.waste_planks) {
                    if (l < rS0) {
                        out.put(Library.blockPosToLong(x, y, z), ModBlocks.waste_planks.getDefaultState());
                        markNotify(x, y, z);
                    }
                    return;
                }
                if (bblock == Blocks.VINE) {
                    out.put(Library.blockPosToLong(x, y, z), Blocks.AIR.getDefaultState());
                    markNotify(x, y, z);
                    continue;
                }
                if (bblock == ModBlocks.ore_uranium || matchesOre(bblock, "oreUranium")) {
                    if (l <= rS6) {
                        if (rnd.nextInt((int) (1 + VersatileConfig.getSchrabOreChance())) == 0 || l < rS7)
                            out.put(Library.blockPosToLong(x, y, z), ModBlocks.ore_schrabidium.getDefaultState());
                        else
                            out.put(Library.blockPosToLong(x, y, z), ModBlocks.ore_uranium_scorched.getDefaultState());
                        markNotify(x, y, z);
                    }
                    return;
                }
                if (bblock == ModBlocks.ore_nether_uranium || matchesOre(bblock, "oreNetherUranium")) {
                    if (l <= rS5) {
                        if (rnd.nextInt((int) (1 + VersatileConfig.getSchrabOreChance())) == 0)
                            out.put(Library.blockPosToLong(x, y, z), ModBlocks.ore_nether_schrabidium.getDefaultState());
                        else
                            out.put(Library.blockPosToLong(x, y, z), ModBlocks.ore_nether_uranium_scorched.getDefaultState());
                        markNotify(x, y, z);
                    }
                    return;
                }
                if (bblock == ModBlocks.ore_gneiss_uranium || matchesOre(bblock, "oreNetherUranium")) {
                    if (l <= rS4) {
                        if (rnd.nextInt((int) (1 + VersatileConfig.getSchrabOreChance() / 2)) == 0)
                            out.put(Library.blockPosToLong(x, y, z), ModBlocks.ore_gneiss_schrabidium.getDefaultState());
                        else
                            out.put(Library.blockPosToLong(x, y, z), ModBlocks.ore_gneiss_uranium_scorched.getDefaultState());
                        markNotify(x, y, z);
                    }
                    return;
                }
                if (bblock == ModBlocks.brick_concrete) {
                    if (rnd.nextInt(60) == 0) {
                        out.put(Library.blockPosToLong(x, y, z), ModBlocks.brick_concrete_broken.getDefaultState());
                        markNotify(x, y, z);
                    }
                    return;
                }
                if (b.getMaterial() == Material.ROCK || b.getMaterial() == Material.IRON) {
                    return;
                }
            }
        }

        private void placeBlockFromDistRadial(double dist, Block b, int x, int y, int z, ConcurrentMap<Long, IBlockState> out, Random rnd) {
            out.put(Library.blockPosToLong(x, y, z), b.getStateFromMeta(tierIndex(dist, rnd, true, false, 0, -1)));
            // EntityFalloutUnderGround.placeBlockFromDist uses the no-arg setBlockState (flag 3),
            // which notifies neighbours; the column-side placeBlockFromDist uses flag 2 and does not.
            markNotify(x, y, z);
        }
    }

    private Material bmaterialOf(IBlockState s) {
        return s.getMaterial();
    }

    private static IBlockState getState(ExtendedBlockStorage[] ebs, int x, int y, int z) {
        if (ebs == null) return Blocks.AIR.getDefaultState();
        int subY = y >> 4;
        if (subY < 0 || subY >= 16) return Blocks.AIR.getDefaultState();
        ExtendedBlockStorage s = ebs[subY];
        if (s == Chunk.NULL_BLOCK_STORAGE || s.isEmpty()) return Blocks.AIR.getDefaultState();
        return s.get(x & 15, y & 15, z & 15);
    }

    // The column phase runs AFTER the radial phase, so it must see the radial's recorded
    // replacements (e.g. sellafield) instead of the untouched mirror block. This mirrors
    // EntityFalloutRain reading the world after EntityFalloutUnderGround already modified it.
    private IBlockState getEffectiveState(int x, int y, int z, ExtendedBlockStorage[] ebs) {
        ConcurrentMap<Long, IBlockState> rep = replacements.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (rep != null) {
            IBlockState s = rep.get(Library.blockPosToLong(x, y, z));
            if (s != null) return s;
        }
        return getState(ebs, x, y, z);
    }

    private static boolean matchesOre(Block block, String oreDictName) {
        return OreDictionary.containsMatch(false, OreDictionary.getOres(oreDictName), new ItemStack(block));
    }

    /**
     * Mirrors {@link BlockPowder#canPlaceBlockAt} without touching the world (reads from the mirror snapshot).
     */
    private static boolean canPlaceFallout(IBlockState below) {
        Block b = below.getBlock();
        if (b == Blocks.ICE || b == Blocks.PACKED_ICE) return false;
        if (below.getMaterial() == Material.LEAVES) return true;
        if (b == ModBlocks.fallout && (below.getValue(BlockPowder.META) & 7) == 7) return true;
        return below.isOpaqueCube() && below.getMaterial().blocksMovement();
    }

    // ===================== lifecycle / NBT =====================

    @Override
    public void setDead() {
        cancel();
        super.setDead();
    }

    public void cancel() {
        cancelling = true;
        U.putIntRelease(this, OFF_COLLECT_FINISHED, 1);
        U.putIntRelease(this, OFF_DESTROY_FINISHED, 1);
        if (activeWorkerTasks == 0) {
            finishCancellation();
        } else {
            ((WorldServer) world).addScheduledTask(this::pollCancelCleanup);
        }
    }

    @ServerThread
    private void pollCancelCleanup() {
        if (activeWorkerTasks == 0) {
            finishCancellation();
        } else {
            ((WorldServer) world).addScheduledTask(this::pollCancelCleanup);
        }
    }

    @Override
    public void cancelJob() {
        cancel();
    }

    @ServerThread
    private void finishCancellation() {
        if (activeWorkerTasks != 0 || !U.compareAndSetInt(this, OFF_CANCEL_CLEANUP, 0, 1)) return;
        replacements.clear();
        notifyPositions.clear();
        chunkLoadQueue.clear();
        applyQueue.clear();
        sectionMaskByChunk.clear();
        releasePoolIfHeld();
        if (U.getAndSetInt(this, OFF_MAP_ACQUIRED, 0) != 0) {
            ChunkUtil.releaseMirrorMap((WorldServer) world);
        }
    }

    synchronized void fail(Throwable cause) {
        if (failure != null) return;
        failure = cause;
        MainRegistry.logger.error("Parallel fallout work failed", cause);
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound nbt) {
        setScale(nbt.getInteger("scale"), nbt.getInteger("radialRadius"), nbt.getInteger("fallingRadius"));
        doFallout = nbt.getBoolean("doFallout");
        doFlood = nbt.getBoolean("doFlood");
        if (nbt.getBoolean("collectDone")) U.putIntRelease(this, OFF_COLLECT_FINISHED, 1);
        if (nbt.getBoolean("destroyDone")) U.putIntRelease(this, OFF_DESTROY_FINISHED, 1);
        if (nbt.hasKey("sectionMask", Constants.NBT.TAG_LIST)) {
            NBTTagList list = nbt.getTagList("sectionMask", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound t = list.getCompoundTagAt(i);
                long ck = ChunkPos.asLong(t.getInteger("cX"), t.getInteger("cZ"));
                sectionMaskByChunk.put(ck, t.getInteger("mask"));
            }
        }
        if (nbt.hasKey("populatedChunks")) {
            for (long cp : ((NBTTagLongArray) nbt.getTag("populatedChunks")).data) {
                populatedChunks.add(cp);
            }
        }
        if (collectFinished != 0 && destroyFinished == 0) {
            // Resume the final packet pass (the conversion already committed its blocks).
            ((WorldServer) world).addScheduledTask(() -> {
                secondPass();
                U.putIntRelease(this, OFF_DESTROY_FINISHED, 1);
                setDead();
            });
        } else if (destroyFinished != 0) {
            ((WorldServer) world).addScheduledTask(this::setDead);
        }
        // else: collectFinished == 0 → onUpdate() re-gathers chunks and calls startWorkers().
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound nbt) {
        nbt.setInteger("scale", getScale());
        nbt.setInteger("radialRadius", radialRadius);
        nbt.setInteger("fallingRadius", fallingRadius);
        nbt.setBoolean("doFallout", doFallout);
        nbt.setBoolean("doFlood", doFlood);
        nbt.setBoolean("collectDone", collectFinished != 0);
        nbt.setBoolean("destroyDone", destroyFinished != 0);
        // Always persist the section mask so a resume can skip already-converted chunks.
        if (!sectionMaskByChunk.isEmpty()) {
            NBTTagList list = new NBTTagList();
            ObjectIterator<Long2IntMap.Entry> iterator = sectionMaskByChunk.long2IntEntrySet().fastIterator();
            while (iterator.hasNext()) {
                Long2IntMap.Entry e = iterator.next();
                NBTTagCompound t = new NBTTagCompound();
                t.setInteger("cX", Library.getChunkPosX(e.getLongKey()));
                t.setInteger("cZ", Library.getChunkPosZ(e.getLongKey()));
                t.setInteger("mask", e.getIntValue());
                list.appendTag(t);
            }
            nbt.setTag("sectionMask", list);
        }
        // Persist the confirmed-populated set so a resume can gate isNeighborhoodPopulated without re-loading every chunk.
        if (!populatedChunks.isEmpty()) {
            long[] arr = new long[populatedChunks.size()];
            int i = 0;
            for (long cp : populatedChunks) arr[i++] = cp;
            nbt.setTag("populatedChunks", new NBTTagLongArray(arr));
        }
    }
}

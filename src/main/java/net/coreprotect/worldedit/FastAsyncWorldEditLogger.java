package net.coreprotect.worldedit;

import java.util.concurrent.atomic.AtomicReferenceArray;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypesCache;

import net.coreprotect.config.Config;
import net.coreprotect.config.ConfigHandler;

final class FastAsyncWorldEditLogger implements IBatchProcessor {
    private static final ProcessorScope SCOPE = resolveScope();
    private final Actor actor;
    private final World eventWorld;
    private final org.bukkit.World world;

    FastAsyncWorldEditLogger(Actor actor, World world, Extent extent) {
        this.actor = actor;
        this.eventWorld = world;
        this.world = BukkitAdapter.adapt(world);
    }

    static Extent wrap(Actor actor, World world, Extent extent) {
        return extent.addProcessor(new FastAsyncWorldEditLogger(actor, world, extent));
    }

    @Override
    public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
        Config config = Config.getConfig(world);
        if (!ConfigHandler.serverRunning || !config.WORLDEDIT) {
            return set;
        }

        int chunkX = chunk.getX() << 4;
        int chunkZ = chunk.getZ() << 4;
        for (int layer = get.getMinSectionPosition(); layer <= get.getMaxSectionPosition(); layer++) {
            char[] changed = set.loadIfPresent(layer);
            if (changed == null) {
                continue;
            }

            char[] original = get.load(layer);
            for (int index = 0; index < changed.length; index++) {
                int newOrdinal = changed[index];
                if (newOrdinal == BlockTypesCache.ReservedIDs.__RESERVED__) {
                    continue;
                }

                int oldOrdinal = original == null ? BlockTypesCache.ReservedIDs.AIR : original[index];
                if (oldOrdinal == BlockTypesCache.ReservedIDs.__RESERVED__) {
                    oldOrdinal = BlockTypesCache.ReservedIDs.AIR;
                }
                if (oldOrdinal == newOrdinal) {
                    continue;
                }

                int x = index & 15;
                int y = (layer << 4) + (index >> 8);
                int z = (index >> 4) & 15;
                Material oldType = material(oldOrdinal);
                BlockData oldBlockData = blockData(oldOrdinal);
                Location location = new Location(world, chunkX + x, y, chunkZ + z);
                BaseBlock baseBlock = WorldEditLogger.needsBaseBlock(oldType, config) ? get.getFullBlock(x, y, z) : null;
                org.bukkit.block.BlockState lowerHalf = WorldEditLogger.isLoggedTopHalf(oldType, oldBlockData) ? getLowerHalf(get, set, x, y, z, chunkX + x, chunkZ + z, oldBlockData) : null;
                WorldEditLogger.postProcess(actor, location, baseBlock, oldType, oldBlockData, dataString(oldOrdinal), material(newOrdinal), blockData(newOrdinal), dataString(newOrdinal), null, false, lowerHalf);
            }
        }
        return set;
    }

    /**
     * The matching lower half of a top half as this edit leaves it, read from the chunk data the processor already holds instead of the world.
     * Null when the block below is not that lower half, or when the edit changes it too, since its own row then covers the double block.
     */
    private org.bukkit.block.BlockState getLowerHalf(IChunkGet get, IChunkSet set, int x, int y, int z, int worldX, int worldZ, BlockData topHalf) {
        int lowerY = y - 1;
        int layer = lowerY >> 4;
        if (layer < get.getMinSectionPosition()) {
            return null;
        }

        int index = ((lowerY & 15) << 8) | (z << 4) | x;
        char[] original = get.load(layer);
        int ordinal = original == null ? BlockTypesCache.ReservedIDs.AIR : original[index];
        if (ordinal == BlockTypesCache.ReservedIDs.__RESERVED__) {
            ordinal = BlockTypesCache.ReservedIDs.AIR;
        }
        char[] changed = set.loadIfPresent(layer);
        if (changed != null && changed[index] != BlockTypesCache.ReservedIDs.__RESERVED__ && changed[index] != ordinal) {
            return null;
        }

        if (!WorldEditLogger.isOtherHalf(topHalf, template(ordinal))) {
            return null;
        }

        return new WorldEditBlockState(new Location(world, worldX, lowerY, worldZ), material(ordinal), blockData(ordinal));
    }

    /*
     * Conversions cached per FAWE block state ordinal: every changed block otherwise converts both states to Bukkit
     * BlockData and builds both data strings. The block registry is fixed once the server is running, so concurrent
     * writers store equal values and a lost race only repeats the work. Material (an enum) and String (final fields)
     * are safely published through plain arrays. BlockData has non-final fields, so it is published through an
     * AtomicReferenceArray: FAWE processes chunks on several threads, and without that a thread could see a template
     * before its fields are initialized. BlockData is also mutable (postProcess may modify it), so callers get a clone
     * and the cached instance never leaves.
     */
    private static final int STATE_COUNT = BlockTypesCache.states.length;
    private static final Material[] MATERIALS = new Material[STATE_COUNT];
    private static final AtomicReferenceArray<BlockData> BLOCK_DATA = new AtomicReferenceArray<>(STATE_COUNT);
    private static final String[] DATA_STRINGS = new String[STATE_COUNT];

    private static BlockData template(int ordinal) {
        BlockData blockData = BLOCK_DATA.get(ordinal);
        if (blockData == null) {
            blockData = BukkitAdapter.adapt(BlockState.getFromOrdinal(ordinal));
            BLOCK_DATA.set(ordinal, blockData);
        }
        return blockData;
    }

    private static BlockData blockData(int ordinal) {
        return template(ordinal).clone();
    }

    private static Material material(int ordinal) {
        Material material = MATERIALS[ordinal];
        if (material == null) {
            material = BukkitAdapter.adapt(BlockState.getFromOrdinal(ordinal).getBlockType());
            MATERIALS[ordinal] = material;
        }
        return material;
    }

    private static String dataString(int ordinal) {
        String dataString = DATA_STRINGS[ordinal];
        if (dataString == null) {
            dataString = template(ordinal).getAsString();
            DATA_STRINGS[ordinal] = dataString;
        }
        return dataString;
    }

    @Override
    public Extent construct(Extent child) {
        return new CoreProtectLogger(actor, eventWorld, child);
    }

    @Override
    public ProcessorScope getScope() {
        return SCOPE;
    }

    private static ProcessorScope resolveScope() {
        try {
            return ProcessorScope.valueOf("READING_BLOCKS");
        }
        catch (IllegalArgumentException exception) {
            return ProcessorScope.valueOf("READING_SET_BLOCKS");
        }
    }
}

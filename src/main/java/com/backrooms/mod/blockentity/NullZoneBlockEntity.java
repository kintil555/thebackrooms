package com.backrooms.mod.blockentity;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * BlockEntity yang menyimpan BlockState asli dari blok yang
 * digantikan oleh NullZone ghost block.
 *
 * Contoh: jika log pohon digantikan ghost block,
 * originalBlockState = minecraft:oak_log[axis=y]
 *
 * Data ini persist ke disk via NBT (NbtUtils.writeBlockState).
 */
public class NullZoneBlockEntity extends BlockEntity {

    /** BlockState asli yang digantikan null zone block ini. */
    private BlockState originalBlockState = Blocks.AIR.defaultBlockState();

    public NullZoneBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NULL_ZONE_BE.get(), pos, state);
    }

    // ─── Getters / Setters ────────────────────────────────────────────────────

    public BlockState getOriginalBlockState() {
        return originalBlockState;
    }

    public void setOriginalBlockState(BlockState state) {
        this.originalBlockState = state;
        setChanged();
    }

    // ─── NBT save/load ────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("originalBlock", NbtUtils.writeBlockState(originalBlockState));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("originalBlock")) {
            try {
                originalBlockState = NbtUtils.readBlockState(
                        registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                        tag.getCompound("originalBlock")
                );
            } catch (Exception e) {
                BackroomsMod.LOGGER.warn(
                        "[Backrooms] Failed to read originalBlock NBT at {}: {}",
                        worldPosition, e.getMessage());
                originalBlockState = Blocks.AIR.defaultBlockState();
            }
        }
    }
}

package com.hbm.blocks.generic;

import com.hbm.blocks.ModBlocks;
import com.hbm.core.HbmCorePlugin;

import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.SoundType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

public class BlockHazardMeta extends BlockHazard {
	
	public static final PropertyInteger META = PropertyInteger.create("meta", 0, 7);
	
	public BlockHazardMeta(Material m, String s){
		super(m, s);
	}

	public BlockHazardMeta(Material mat, SoundType type, String s) {
		super(mat, type, s);
	}

	@Override
	public MapColor getMapColor(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
		if(this == ModBlocks.waste_clay) return MapColor.CLAY;
		if(this == ModBlocks.waste_snow_block) return MapColor.SNOW;
		if(this == ModBlocks.waste_sandstone) return MapColor.SAND;
		if(this == ModBlocks.waste_sandstone_red) return MapColor.ADOBE;
		if(this == ModBlocks.waste_terracotta) return MapColor.ADOBE;
		if(this == ModBlocks.sellafield_slaked) return HbmCorePlugin.SELLAFIELD_SLAKED;
		if(this == ModBlocks.sellafield_0) return HbmCorePlugin.SELLAFIELD_SLAKED;
		if(this == ModBlocks.sellafield_1) return HbmCorePlugin.SELLAFIELD_1;
		if(this == ModBlocks.sellafield_2) return HbmCorePlugin.SELLAFIELD_2;
		if(this == ModBlocks.sellafield_3) return HbmCorePlugin.SELLAFIELD_2;
		if(this == ModBlocks.sellafield_4) return HbmCorePlugin.SELLAFIELD_CORE;
		if(this == ModBlocks.sellafield_core) return HbmCorePlugin.SELLAFIELD_CORE;
		return super.getMapColor(state, worldIn, pos);
	}

	@Override
	protected BlockStateContainer createBlockState() {
		return new BlockStateContainer(this, new IProperty[]{META});
	}
	
	@Override
	public int getMetaFromState(IBlockState state) {
		return state.getValue(META);
	}
	
	@Override
	public IBlockState getStateFromMeta(int meta) {
		return this.getDefaultState().withProperty(META, meta);
	}
}
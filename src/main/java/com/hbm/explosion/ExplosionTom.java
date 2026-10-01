package com.hbm.explosion;

import java.util.HashMap;
import java.util.Map;

import com.hbm.config.CompatibilityConfig;
import com.hbm.config.WorldConfig;
import com.hbm.blocks.ModBlocks;
import com.hbm.world.WorldUtil;
import com.hbm.world.biome.BiomeGenDustWastes;

import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;

public class ExplosionTom
{
	public int posX;
	public int posY;
	public int posZ;
	public int lastposX = 0;
	public int lastposZ = 0;
	public int radius;
	public int radius2;
	public World world;
	private int n = 1;
	private int nlimit;
	private int shell;
	private int leg;
	private int element;
	private final Map<Long, Integer> biomeChunkCounts = new HashMap<Long, Integer>();
	private final Map<Long, Integer> biomeChunkTotals = new HashMap<Long, Integer>();
	
	public void saveToNbt(NBTTagCompound nbt, String name) {
		nbt.setInteger(name + "posX", posX);
		nbt.setInteger(name + "posY", posY);
		nbt.setInteger(name + "posZ", posZ);
		nbt.setInteger(name + "lastposX", lastposX);
		nbt.setInteger(name + "lastposZ", lastposZ);
		nbt.setInteger(name + "radius", radius);
		nbt.setInteger(name + "radius2", radius2);
		nbt.setInteger(name + "n", n);
		nbt.setInteger(name + "nlimit", nlimit);
		nbt.setInteger(name + "shell", shell);
		nbt.setInteger(name + "leg", leg);
		nbt.setInteger(name + "element", element);
	}
	
	public void readFromNbt(NBTTagCompound nbt, String name) {
		posX = nbt.getInteger(name + "posX");
		posY = nbt.getInteger(name + "posY");
		posZ = nbt.getInteger(name + "posZ");
		lastposX = nbt.getInteger(name + "lastposX");
		lastposZ = nbt.getInteger(name + "lastposZ");
		radius = nbt.getInteger(name + "radius");
		radius2 = nbt.getInteger(name + "radius2");
		n = nbt.getInteger(name + "n");
		nlimit = nbt.getInteger(name + "nlimit");
		shell = nbt.getInteger(name + "shell");
		leg = nbt.getInteger(name + "leg");
		element = nbt.getInteger(name + "element");
	}
	
	public ExplosionTom(int x, int y, int z, World world, int rad)
	{
		this.posX = x;
		this.posY = y;
		this.posZ = z;
		
		this.world = world;
		
		this.radius = rad;
		this.radius2 = this.radius * this.radius;

		this.nlimit = this.radius2 * 4;
	}
	
	public boolean update() {
		if(!CompatibilityConfig.isWarDim(world)){
			return true;
		}
		breakColumn(this.lastposX, this.lastposZ);
		this.shell = (int) Math.floor((Math.sqrt(n) + 1) / 2);
		int shell2 = this.shell * 2;
		this.leg = (int) Math.floor((this.n - (shell2 - 1) * (shell2 - 1)) / shell2);
		this.element = (this.n - (shell2 - 1) * (shell2 - 1)) - shell2 * this.leg - this.shell + 1;
		this.lastposX = this.leg == 0 ? this.shell : this.leg == 1 ? -this.element : this.leg == 2 ? -this.shell : this.element;
		this.lastposZ = this.leg == 0 ? this.element : this.leg == 1 ? this.shell : this.leg == 2 ? -this.element : -this.shell;
		this.n++;
		return this.n > this.nlimit;
	}

	private void breakColumn(int x, int z) {
		int dist = this.radius2 - (x * x + z * z);

		if(dist > 0) {
			int pX = posX + x;
			int pZ = posZ + z;
			double X = Math.pow((this.posX - pX), 2);
			double Z = Math.pow((this.posZ - pZ), 2);
			double distance = Math.sqrt(X + Z); // Distance calculations used for crater rim stuff

			int y = 256;
			int terrain = posY - 1;

			double cA = (terrain - Math.pow(Math.E, -Math.pow(Math.sqrt(x * x + z * z), 2) / 40000) * 13) + world.rand.nextInt(2); // Basic crater bowl shape
			double cB = cA + Math.pow(Math.E, -Math.pow(Math.sqrt(x * x + z * z) - 200, 2) / 400) * 13 ;// Crater peak ring
			int craterFloor = (int) (cB + Math.pow(Math.E, -Math.pow(Math.sqrt(x * x + z * z) - 500, 2) / 2000) * 37); // Crater rim
			MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for(int i = 256; i > 0; i--) {
				if(i == craterFloor || !world.isAirBlock(pos.setPos(pX, i, pZ))) {
					y = i;
					break;
				}
			}
			int height = terrain - 14;
			int offset = 20;
			int threshold = (int) ((float) Math.sqrt(x * x + z * z) * (float) (height + offset) / (float) this.radius) + world.rand.nextInt(2) - offset;

			if(y < terrain + 1) y = terrain + 1;

			Material m;
			while(y > threshold) {

				if(y == 0)
					break;
				if(y <= craterFloor) {
					pos.setPos(pX, y, pZ);
					if(world.rand.nextInt(200) == 0) {
						setBlockAndNotify(pos, ModBlocks.ore_tektite_osmiridium.getDefaultState());
					} else {
						setBlockAndNotify(pos, ModBlocks.tektite.getDefaultState());
					}

				} else {
					if(y > terrain + 1) {
						if(distance < 500) {
							for(int i = -2; i < 3; i++) {
								for(int j = -2; j < 3; j++) {
									for(int k = -2; k < 3; k++) {
										pos.setPos(pX + i, y + j, pZ + k);
										m = world.getBlockState(pos).getMaterial();
										if(m == Material.WATER || m == Material.ICE || m == Material.SNOW || m.getCanBurn()) {
											world.removeTileEntity(pos);
											world.setBlockToAir(pos);
											world.setBlockToAir(pos.setPos(pX, y, pZ));
										}
									}
								}
							}
							world.removeTileEntity(pos.setPos(pX, y, pZ));
							setBlockAndNotify(pos.setPos(pX, y, pZ), Blocks.AIR.getDefaultState());
						}
					} else {
						for(int i = -2; i < 3; i++) {
							for(int j = -2; j < 3; j++) {
								for(int k = -2; k < 3; k++) {
									m = world.getBlockState(pos.setPos(pX + i, y + j, pZ + k)).getMaterial();
									IBlockState nb = world.getBlockState(pos.setPos(pX + i, y, pZ + k));
									Material nm = nb.getMaterial();
									if(m == Material.WATER || m == Material.ICE || nb.getBlock() == Blocks.AIR || nm == Material.SNOW || nm.getCanBurn()) {
										setBlockAndNotify(pos.setPos(pX + i, y, pZ + k), Blocks.LAVA.getDefaultState());
										setBlockAndNotify(pos.setPos(pX, y, pZ), Blocks.LAVA.getDefaultState());
									}
								}
							}
						}
						setBlockAndNotify(pos.setPos(pX, y, pZ), Blocks.LAVA.getDefaultState());
					}
				}
				y--;
			}

			if (WorldConfig.enableDustWastesBiome) {
				WorldUtil.setBiome(world, pX, pZ, BiomeGenDustWastes.dustWastes);
				int cx = pX >> 4;
				int cz = pZ >> 4;
				long cp = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
				int total = biomeChunkTotals.getOrDefault(cp, -1);
				if (total < 0) {
					total = 0;
					for (int tx = 0; tx < 16; tx++) {
						for (int tz = 0; tz < 16; tz++) {
							int dx = (cx << 4) + tx - posX;
							int dz = (cz << 4) + tz - posZ;
							if (dx * dx + dz * dz <= radius2) total++;
						}
					}
					biomeChunkTotals.put(cp, total);
				}
				int count = biomeChunkCounts.getOrDefault(cp, 0) + 1;
				if (count >= total) {
					WorldUtil.syncBiomeChange(world, cx, cz);
					biomeChunkCounts.remove(cp);
					biomeChunkTotals.remove(cp);
				} else {
					biomeChunkCounts.put(cp, count);
				}
			}
		}
	}

	private void setBlockAndNotify(MutableBlockPos pos, IBlockState state) {
		Block oldBlock = world.getBlockState(pos).getBlock();
		world.setBlockState(pos, state, 2);
		for(EnumFacing facing : EnumFacing.values()) {
			BlockPos np = pos.offset(facing);
			if(world.isBlockLoaded(np)) {
				Block b = world.getBlockState(np).getBlock();
				if(b != Blocks.WATER && b != Blocks.FLOWING_WATER && !hasWaterNeighbor(np)) {
					world.neighborChanged(np, oldBlock, pos);
				}
			}
		}
	}

	private boolean hasWaterNeighbor(BlockPos pos) {
		for(EnumFacing facing : EnumFacing.values()) {
			BlockPos np = pos.offset(facing);
			if(world.isBlockLoaded(np)) {
				Block b = world.getBlockState(np).getBlock();
				if(b == Blocks.WATER || b == Blocks.FLOWING_WATER) {
					return true;
				}
			}
		}
		return false;
	}
}

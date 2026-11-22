package com.hbm.dynamic_texture;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.lwjgl.opengl.GL11;

import com.hbm.dynamic_texture.DynamicTextureData.ItemModelBinding;
import com.hbm.dynamic_texture.DynamicTextureData.Layer;
import com.hbm.dynamic_texture.DynamicTextureData.TextureRegion;
import com.hbm.dynamic_texture.DynamicTextureData.Tint;
import com.hbm.dynamic_texture.DynamicTextureData.TintMode;
import com.hbm.dynamic_texture.DynamicTextureData.TintParams;
import com.hbm.lib.RefStrings;
import com.hbm.main.ResourceManager;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ColorHandlerEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class DynamicTextureRegistry {

	private static final Map<ResourceLocation, DynamicTextureData> TEXTURE_ENTRIES = new LinkedHashMap<>();
	private static final Map<String, Integer> TEXTURE_CACHE = new HashMap<>();

	static {
		registerBlocks();
		registerExternalTextures();
	}

	public static void registerTexture(ResourceLocation base, DynamicTextureData data) {
		data.base(base);
		TEXTURE_ENTRIES.put(base, data);
	}

	private static void registerBlocks() {
		DynamicTextureData.registerDefaults();
	}

	private static void registerExternalTextures() {
		registerTexture(ResourceManager.fatman_mininuke_tex, new DynamicTextureData()
				.region(0, 20, 96, 4, TintMode.HSB)
				.variant("NUKE_SAFE", 0.2614F, 0.73F)
				.variant("NUKE_LOW", 0.5401F, 0.73F)
				.variant("NUKE_HIGH", 0.0219F, 0.73F)
				.variant("NUKE_PUMPKIN", 0.0865F, 0.73F));
		registerTexture(ResourceManager.fatman_minimirv_tex, new DynamicTextureData()
				.region(0, 12, 32, 2, TintMode.HSB)
				.variant("MIRV_SAFE", 0.2793F, 0.82F)
				.variant("MIRV_LOW", 0.5575F, 0.82F)
				.variant("MIRV_HIGH", 0.0368F, 0.82F)
				.variant("MIRV_SPECIAL", 0.8621F, 0.82F));

		DynamicTextureData rbmk = new DynamicTextureData()
				.region(16, 30, 16, 2, TintMode.MULTIPLY)
				.region(0, 0, 16, 16, TintMode.MULTIPLY)
				.variant("RED", 0xFF0000)
				.variant("YELLOW", 0xFFD800)
				.variant("GREEN", 0x4CFF00)
				.variant("BLUE", 0x0026FF)
				.variant("PURPLE", 0xB200FF);
		registerTexture(new ResourceLocation(RefStrings.MODID, "textures/blocks/rbmk/rbmk_control.png"), rbmk);
		registerTexture(new ResourceLocation(RefStrings.MODID, "textures/blocks/rbmk/rbmk_control_mod.png"), rbmk);
	}

	@SideOnly(Side.CLIENT)
	public static void registerSprites(TextureStitchEvent.Pre evt) {
		clearTextureCache();
		for(DynamicTextureData data : DynamicTextureData.BLOCK_DATA.values()) {
			for(Layer layer : data.layers) {
				for(int meta = layer.metaFrom; meta <= layer.metaTo; meta++) {
					evt.getMap().setTextureEntry(layer.transform.create(layer.name(meta), layer.sources(), layer.params()));
				}
			}
		}
	}

	@SideOnly(Side.CLIENT)
	public static void registerBlockColors(ColorHandlerEvent.Block evt) {
		for(Map.Entry<Block, DynamicTextureData> entry : DynamicTextureData.BLOCK_DATA.entrySet()) {
			if(entry.getValue().blockTints.isEmpty()) continue;
			evt.getBlockColors().registerBlockColorHandler((state, world, pos, tintIndex) -> {
				int index = externalQuery(world) ? representativeIndex(entry.getValue().blockTints, tintIndex) : tintIndex;
				for(Tint tint : entry.getValue().blockTints) {
					if(tint.matchesBlock(state, index)) return tint.color(state);
				}
				return 0xFFFFFF;
			}, entry.getKey());
		}
	}

	/** Map mods query colours through a stand-in block access and a fixed tint index; report the top-face tint instead. */
	private static boolean externalQuery(IBlockAccess world) {
		return world != null && !(world instanceof World) && !(world instanceof ChunkCache);
	}

	private static int representativeIndex(List<Tint> tints, int fallback) {
		boolean top = false, primary = false;
		for(Tint tint : tints) {
			if(tint.index == 2) top = true;
			else if(tint.index == 0) primary = true;
		}
		return top ? 2 : (primary ? 0 : fallback);
	}

	@SideOnly(Side.CLIENT)
	public static void registerItemColors(ColorHandlerEvent.Item evt) {
		for(Map.Entry<Block, DynamicTextureData> entry : DynamicTextureData.BLOCK_DATA.entrySet()) {
			if(entry.getValue().itemTints.isEmpty()) continue;
			Item item = Item.getItemFromBlock(entry.getKey());
			if(item == null) continue;
			evt.getItemColors().registerItemColorHandler((stack, tintIndex) -> {
				for(Tint tint : entry.getValue().itemTints) {
					if(tint.matchesItem(stack, tintIndex)) return tint.color(stack);
				}
				return 0xFFFFFF;
			}, item);
		}
	}

	@SideOnly(Side.CLIENT)
	public static void registerItemModels() {
		for(Map.Entry<Block, DynamicTextureData> entry : DynamicTextureData.BLOCK_DATA.entrySet()) {
			if(entry.getValue().itemModels.isEmpty()) continue;
			Item item = Item.getItemFromBlock(entry.getKey());
			if(item == null) continue;
			for(ItemModelBinding binding : entry.getValue().itemModels) {
				for(int damage = binding.fromDamage; damage <= binding.toDamage; damage++) {
					net.minecraftforge.client.model.ModelLoader.setCustomModelResourceLocation(item, damage, binding.model);
				}
			}
		}
	}

	@SideOnly(Side.CLIENT)
	public static int getTexture(ResourceLocation base, String variant) {
		if(variant == null || "NONE".equals(variant)) return -1;
		DynamicTextureData data = TEXTURE_ENTRIES.get(base);
		if(data == null) return -1;
		TintParams params = data.variants.get(variant);
		if(params == null) return -1;

		String key = base.toString() + "|" + variant;
		Integer cached = TEXTURE_CACHE.get(key);
		if(cached != null) return cached;

		try {
			BufferedImage image = buildTexture(base, data, params);
			if(image == null) return -1;
			DynamicTexture texture = new DynamicTexture(image);
			int gl = texture.getGlTextureId();
			GlStateManager.bindTexture(gl);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			TEXTURE_CACHE.put(key, gl);
			return gl;
		} catch(Exception ex) {
			FMLClientHandler.instance().trackBrokenTexture(base, ex.getMessage());
			return -1;
		}
	}

	private static BufferedImage buildTexture(ResourceLocation base, DynamicTextureData data, TintParams params) throws IOException {
		try(InputStream stream = Minecraft.getMinecraft().getResourceManager().getResource(base).getInputStream()) {
			BufferedImage source = ImageIO.read(stream);
			if(source == null) return null;
			int width = source.getWidth();
			int height = source.getHeight();
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			image.setRGB(0, 0, width, height, source.getRGB(0, 0, width, height, null, 0, width), 0, width);
			for(TextureRegion region : data.regions) {
				int x1 = Math.min(image.getWidth(), region.x + region.width);
				int y1 = Math.min(image.getHeight(), region.y + region.height);
				for(int y = Math.max(0, region.y); y < y1; y++) {
					for(int x = Math.max(0, region.x); x < x1; x++) {
						image.setRGB(x, y, apply(image.getRGB(x, y), region.mode, params));
					}
				}
			}
			return image;
		}
	}

	private static int apply(int pixel, TintMode mode, TintParams params) {
		int a = (pixel >>> 24) & 0xFF;
		int r = (pixel >> 16) & 0xFF;
		int g = (pixel >> 8) & 0xFF;
		int b = pixel & 0xFF;
		if(mode == TintMode.REPLACE) {
			r = (params.color >> 16) & 0xFF;
			g = (params.color >> 8) & 0xFF;
			b = params.color & 0xFF;
		} else if(mode == TintMode.MULTIPLY) {
			r = r * ((params.color >> 16) & 0xFF) / 255;
			g = g * ((params.color >> 8) & 0xFF) / 255;
			b = b * (params.color & 0xFF) / 255;
		} else {
			float[] hsb = new float[3];
			Color.RGBtoHSB(r, g, b, hsb);
			hsb[0] = params.hue;
			hsb[1] = Math.max(hsb[1], params.saturation);
			int rgb = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
			r = (rgb >> 16) & 0xFF;
			g = (rgb >> 8) & 0xFF;
			b = rgb & 0xFF;
		}
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static void clearTextureCache() {
		for(Integer gl : TEXTURE_CACHE.values()) {
			if(gl != null && gl >= 0) TextureUtil.deleteTexture(gl);
		}
		TEXTURE_CACHE.clear();
	}
}
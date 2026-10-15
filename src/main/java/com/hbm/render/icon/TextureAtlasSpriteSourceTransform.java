package com.hbm.render.icon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.PngSizeInfo;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.compress.utils.IOUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.function.Function;

/** Single custom-sprite loader: transforms one or two source textures per-pixel. */
public class TextureAtlasSpriteSourceTransform extends TextureAtlasSprite {

	@FunctionalInterface
	private interface PixelTransform {
		int apply(int basePixel, int overlayPixel);
	}

	private final String[] sources;
	private final PixelTransform transform;
	private final String basePath = "textures";

	private TextureAtlasSpriteSourceTransform(String spriteName, PixelTransform transform, String... sources) {
		super(spriteName);
		this.transform = transform;
		this.sources = sources;
	}

	public static TextureAtlasSpriteSourceTransform grayscale(String spriteName, String source) {
		return new TextureAtlasSpriteSourceTransform(spriteName, (base, overlay) -> {
			int r = (base >> 16) & 0xFF;
			int g = (base >> 8) & 0xFF;
			int b = base & 0xFF;
			int lum = (r * 299 + g * 587 + b * 114) / 1000;
			return (base & 0xFF000000) | (lum << 16) | (lum << 8) | lum;
		}, source);
	}

	public static TextureAtlasSpriteSourceTransform hueShift(String spriteName, String source, float hue, float saturation) {
		return new TextureAtlasSpriteSourceTransform(spriteName, (base, overlay) -> {
			int a = (base >> 24) & 0xFF;
			float[] hsb = new float[3];
			Color.RGBtoHSB((base >> 16) & 0xFF, (base >> 8) & 0xFF, base & 0xFF, hsb);
			hsb[0] = hue;
			hsb[1] = Math.max(hsb[1], saturation);
			return (a << 24) | (Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]) & 0xFFFFFF);
		}, source);
	}

	public static TextureAtlasSpriteSourceTransform multipass(String spriteName, String baseTexture, String overlayPath) {
		return new TextureAtlasSpriteSourceTransform(spriteName, (base, overlay) -> {
			int alpha = (overlay >> 24) & 0xFF;
			if(alpha <= 0) return base;
			int red = (alpha * ((overlay >> 16) & 0xFF) + (255 - alpha) * ((base >> 16) & 0xFF)) / 255;
			int green = (alpha * ((overlay >> 8) & 0xFF) + (255 - alpha) * ((base >> 8) & 0xFF)) / 255;
			int blue = (alpha * (overlay & 0xFF) + (255 - alpha) * (base & 0xFF)) / 255;
			return (base & 0xFF000000) | (red << 16) | (green << 8) | blue;
		}, baseTexture, overlayPath);
	}

	@Override
	public boolean hasCustomLoader(IResourceManager manager, ResourceLocation location) {
		return true;
	}

	private ResourceLocation completeResourceLocation(String source) {
		ResourceLocation loc = new ResourceLocation(source);
		return new ResourceLocation(loc.getNamespace(), String.format("%s/%s%s", this.basePath, loc.getPath(), ".png"));
	}

	@Override
	public boolean load(IResourceManager man, ResourceLocation resourcelocation, Function<ResourceLocation, TextureAtlasSprite> textureGetter) {
		ResourceLocation[] fulls = new ResourceLocation[this.sources.length];
		IResource[] resources = new IResource[this.sources.length];
		try {
			fulls[0] = this.completeResourceLocation(this.sources[0]);
			resources[0] = man.getResource(fulls[0]);
			PngSizeInfo info = PngSizeInfo.makeFromResource(resources[0]);
			this.loadSprite(info, resources[0].getMetadata("animation") != null);
			for(int i = 1; i < this.sources.length; i++) {
				fulls[i] = this.completeResourceLocation(this.sources[i]);
				resources[i] = man.getResource(fulls[i]);
			}
			for(int i = 0; i < this.sources.length; i++) {
				resources[i] = man.getResource(fulls[i]);
			}
			this.loadTransformFrames(resources, Minecraft.getMinecraft().getTextureMapBlocks().getMipmapLevels() + 1);
		} catch(RuntimeException | IOException e) {
			net.minecraftforge.fml.client.FMLClientHandler.instance().trackBrokenTexture(fulls[0], e.getMessage());
			return true;
		} finally {
			for(IResource r : resources) IOUtils.closeQuietly(r);
		}
		return false;
	}

	private void loadTransformFrames(IResource[] resources, int mipmapLevels) throws IOException {
		BufferedImage base = ImageIO.read(resources[0].getInputStream());
		BufferedImage overlay = this.sources.length > 1 ? ImageIO.read(resources[1].getInputStream()) : null;
		int w = base.getWidth();
		int h = base.getHeight();
		int[][] data = new int[mipmapLevels][];
		data[0] = new int[w * h];
		int[] pixels = base.getRGB(0, 0, w, h, null, 0, w);
		for(int y = 0; y < h; y++) {
			for(int x = 0; x < w; x++) {
				int i = y * w + x;
				int overlayPixel = overlay != null ? overlay.getRGB(x, y) : 0;
				data[0][i] = this.transform.apply(pixels[i], overlayPixel);
			}
		}
		this.framesTextureData.add(data);
	}
}
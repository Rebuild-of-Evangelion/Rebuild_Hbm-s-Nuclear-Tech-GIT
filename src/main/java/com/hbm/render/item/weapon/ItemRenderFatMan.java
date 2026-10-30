package com.hbm.render.item.weapon;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.lwjgl.opengl.GL11;

import com.hbm.handler.BulletConfigSyncingUtil;
import com.hbm.items.weapon.ItemGunBase;
import com.hbm.lib.RefStrings;
import com.hbm.main.ResourceManager;
import com.hbm.render.anim.HbmAnimations;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;

public class ItemRenderFatMan extends ItemRenderWeaponBase {

	private static final Map<Integer, Integer> NUKE_TINT_CACHE = new HashMap<>();
	private static final Map<Integer, Integer> MIRV_TINT_CACHE = new HashMap<>();

	/** Binds the (possibly tinted) mini-nuke texture for the given bullet config id. */
	public static void bindNuke(int config) {
		float hue = nukeHueOf(config);
		if(Float.isNaN(hue)) {
			Minecraft.getMinecraft().getTextureManager().bindTexture(ResourceManager.fatman_mininuke_tex);
			return;
		}
		Integer gl = NUKE_TINT_CACHE.get(config);
		if(gl == null) {
			gl = createTint(ResourceManager.fatman_mininuke_tex, 0, 20, 96, 4, hue, 0.73F);
			NUKE_TINT_CACHE.put(config, gl == null ? -1 : gl);
		}
		if(gl == null || gl < 0) {
			Minecraft.getMinecraft().getTextureManager().bindTexture(ResourceManager.fatman_mininuke_tex);
		} else {
			GlStateManager.bindTexture(gl);
		}
	}

	/** Binds the (possibly tinted) mini-MIRV texture for the given bullet config id. */
	public static void bindMirv(int config) {
		float hue = mirvHueOf(config);
		if(Float.isNaN(hue)) {
			Minecraft.getMinecraft().getTextureManager().bindTexture(ResourceManager.fatman_minimirv_tex);
			return;
		}
		Integer gl = MIRV_TINT_CACHE.get(config);
		if(gl == null) {
			gl = createTint(ResourceManager.fatman_minimirv_tex, 0, 12, 32, 2, hue, 0.82F);
			MIRV_TINT_CACHE.put(config, gl == null ? -1 : gl);
		}
		if(gl == null || gl < 0) {
			Minecraft.getMinecraft().getTextureManager().bindTexture(ResourceManager.fatman_minimirv_tex);
		} else {
			GlStateManager.bindTexture(gl);
		}
	}

	private static float nukeHueOf(int config) {
		if(config == BulletConfigSyncingUtil.NUKE_SAFE || config == BulletConfigSyncingUtil.NUKE_PROTO_SAFE) return 0.2614F;
		if(config == BulletConfigSyncingUtil.NUKE_LOW || config == BulletConfigSyncingUtil.NUKE_PROTO_LOW) return 0.5401F;
		if(config == BulletConfigSyncingUtil.NUKE_HIGH || config == BulletConfigSyncingUtil.NUKE_PROTO_HIGH) return 0.0219F;
		if(config == BulletConfigSyncingUtil.NUKE_PUMPKIN || config == BulletConfigSyncingUtil.NUKE_PROTO_PUMPKIN) return 0.0865F;
		return Float.NaN;
	}

	private static float mirvHueOf(int config) {
		if(config == BulletConfigSyncingUtil.NUKE_MIRV_SAFE) return 0.2793F;
		if(config == BulletConfigSyncingUtil.NUKE_MIRV_LOW) return 0.5575F;
		if(config == BulletConfigSyncingUtil.NUKE_MIRV_HIGH) return 0.0368F;
		if(config == BulletConfigSyncingUtil.NUKE_MIRV_SPECIAL) return 0.8621F;
		return Float.NaN;
	}

	private static Integer createTint(ResourceLocation base, int x0, int y0, int w, int h, float hue, float saturation) {
		BufferedImage img;
		try(InputStream is = Minecraft.getMinecraft().getResourceManager().getResource(base).getInputStream()) {
			img = ImageIO.read(is);
		} catch(Exception e) {
			return null;
		}
		if(img == null) return null;
		int iw = img.getWidth();
		int ih = img.getHeight();
		int[] pixels = img.getRGB(0, 0, iw, ih, null, 0, iw);
		int xe = Math.min(x0 + w, iw);
		int ye = Math.min(y0 + h, ih);
		for(int y = y0; y < ye; y++) {
			for(int x = x0; x < xe; x++) {
				int p = pixels[y * iw + x];
				int a = (p >> 24) & 0xFF;
				float[] hsb = new float[3];
				Color.RGBtoHSB((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF, hsb);
				hsb[0] = hue;
				hsb[1] = Math.max(hsb[1], saturation);
				int rgb = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
				pixels[y * iw + x] = (a << 24) | (rgb & 0xFFFFFF);
			}
		}
		DynamicTexture dt = new DynamicTexture(iw, ih);
		int[] data = dt.getTextureData();
		System.arraycopy(pixels, 0, data, 0, iw * ih);
		dt.updateDynamicTexture();
		GlStateManager.bindTexture(dt.getGlTextureId());
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		return dt.getGlTextureId();
	}

	@Override
	public boolean useIdentityTransform() {
		return true;
	}

	@Override
	public void renderByItem(ItemStack stack) {
		GlStateManager.pushMatrix();

		if(type == TransformType.FIRST_PERSON_RIGHT_HAND || type == TransformType.FIRST_PERSON_LEFT_HAND) {
			EnumHand hand = type == TransformType.FIRST_PERSON_LEFT_HAND ? EnumHand.OFF_HAND : EnumHand.MAIN_HAND;
			ItemGunBase.updateAiming(hand, ItemGunBase.getIsAiming(hand));
		}

		super.renderByItem(stack);

		GlStateManager.popMatrix();
	}

	@Override
	protected float getTurnMagnitude(ItemStack stack, EnumHand hand) {
		return ItemGunBase.getIsAiming(hand) ? 2.5F : -0.5F;
	}

	@Override
	protected void setupFirstPerson(ItemStack stack, EnumHand hand, float interp) {
		GlStateManager.translate(0, 0, 0.875);
		float offset = 0.8F;
		standardAimingTransform(hand, interp, -1.5F * offset, -1.25F * offset, 0.5F * offset, -1F * offset, -1.25F * offset, 0F * offset);
	}

	@Override
	protected void setupThirdPerson(ItemStack stack) {
		super.setupThirdPerson(stack);
		double scale = 2.5D;
		GlStateManager.scale(scale, scale, scale);
		GlStateManager.translate(-0.5, 0.5, -3);
	}

	@Override
	protected void setupInv(ItemStack stack) {
		super.setupInv(stack);
		double scale = 1.375D;
		GlStateManager.scale(scale, scale, scale);
		GlStateManager.rotate(25F, 1, 0, 0);
		GlStateManager.rotate(45F, 0, 1, 0);
		GlStateManager.translate(0, -0.5, 0);
	}

	@Override
	public float getViewFOV(float fov, EnumHand hand) {
		float interp = Minecraft.getMinecraft().getRenderPartialTicks();
		float progress = ItemGunBase.getPrevAimingProgress(hand) + (ItemGunBase.getAimingProgress(hand) - ItemGunBase.getPrevAimingProgress(hand)) * interp;
		return fov * (1 - progress * 0.33F);
	}

	@Override
	protected void renderFirstPerson(ItemStack stack, EnumHand hand) {
		Minecraft.getMinecraft().getTextureManager().bindTexture(ResourceManager.fatman_tex);
		double scale = 0.5D;
		GlStateManager.scale(scale, scale, scale);

		boolean isLoaded = ItemGunBase.getMag(stack) > 0;

		double[] equip = HbmAnimations.getRelevantTransformation("EQUIP", hand);
		double[] lid = HbmAnimations.getRelevantTransformation("LID", hand);
		double[] nuke = HbmAnimations.getRelevantTransformation("NUKE", hand);
		double[] piston = HbmAnimations.getRelevantTransformation("PISTON", hand);
		double[] handle = HbmAnimations.getRelevantTransformation("HANDLE", hand);
		double[] gauge = HbmAnimations.getRelevantTransformation("GAUGE", hand);

		GlStateManager.translate(0, 1, -2);
		GlStateManager.rotate((float) equip[0], 1, 0, 0);
		GlStateManager.translate(0, -1, 2);

		GlStateManager.shadeModel(GL11.GL_SMOOTH);

		ResourceManager.fatman.renderPart("Launcher");

		GlStateManager.pushMatrix();
		GlStateManager.translate(0, 0, handle[2]);
		ResourceManager.fatman.renderPart("Handle");
		GlStateManager.translate(0.4375, -0.875, 0);
		GlStateManager.rotate((float) gauge[2], 0, 0, 1);
		GlStateManager.translate(-0.4375, 0.875, 0);
		ResourceManager.fatman.renderPart("Gauge");
		GlStateManager.popMatrix();

		GlStateManager.pushMatrix();
		GlStateManager.translate(0.25, 0.125, 0);
		GlStateManager.rotate((float) lid[2], 0, 0, 1);
		GlStateManager.translate(-0.25, -0.125, 0);
		ResourceManager.fatman.renderPart("Lid");
		GlStateManager.popMatrix();

		GlStateManager.pushMatrix();
		GlStateManager.translate(0, 0, piston[2]);
		if(!isLoaded && piston[2] == 0) GlStateManager.translate(0, 0, 3);
		ResourceManager.fatman.renderPart("Piston");
		GlStateManager.popMatrix();

		if(isLoaded || nuke[0] != 0 || nuke[1] != 0 || nuke[2] != 0) {
			GlStateManager.pushMatrix();
			GlStateManager.translate(nuke[0], nuke[1], nuke[2]);
			renderNuke(stack);
			GlStateManager.popMatrix();
		}

		GlStateManager.shadeModel(GL11.GL_FLAT);
	}

	private void renderNuke(ItemStack stack) {
		ItemGunBase gun = (ItemGunBase) stack.getItem();
		int magType = ItemGunBase.getMagType(stack);
		boolean balefire = false;
		if(magType >= 0 && magType < gun.getCurrentConfig(stack).config.size()) {
			int ammoId = gun.getCurrentConfig(stack).config.get(magType);
			if(ammoId == BulletConfigSyncingUtil.NUKE_MIRV_NORMAL || ammoId == BulletConfigSyncingUtil.NUKE_MIRV_LOW || ammoId == BulletConfigSyncingUtil.NUKE_MIRV_HIGH || ammoId == BulletConfigSyncingUtil.NUKE_MIRV_SAFE || ammoId == BulletConfigSyncingUtil.NUKE_MIRV_SPECIAL) {
				bindMirv(ammoId);
				ResourceManager.fatman.renderPart("MiniMIRV");
				return;
			}
			if(ammoId == BulletConfigSyncingUtil.NUKE_AMAT || ammoId == BulletConfigSyncingUtil.NUKE_PROTO_AMAT) {
				balefire = true;
			}
		}
		if(balefire) {
			Minecraft.getMinecraft().getTextureManager().bindTexture(ResourceManager.fatman_balefire_tex);
		} else {
			int ammoId = magType >= 0 && magType < gun.getCurrentConfig(stack).config.size() ? gun.getCurrentConfig(stack).config.get(magType) : -1;
			bindNuke(ammoId);
		}
		ResourceManager.fatman.renderPart("MiniNuke");
		ResourceManager.fatman.renderPart("MiniNukeFins");
		if(balefire) {
			renderBalefireGlint();
		}
	}

	private void renderBalefireGlint() {
		Minecraft mc = Minecraft.getMinecraft();

		// Preserve original state
		boolean wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
		boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
		boolean wasDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
		int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
		int prevBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
		int prevBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);

		// Kill the lightmap multiply; only the enable flag is touched, unlike disableLightmap/enableLightmap
		GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
		boolean wasLightmapOn = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
		GlStateManager.disableTexture2D();
		GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);

		mc.getTextureManager().bindTexture(new ResourceLocation(RefStrings.MODID, "textures/misc/glintBF.png"));

		float scale = 2F;
		float r = 0F, g = 0.8F, b = 0.15F;
		float speed = -6F;
		float glintColor = 0.76F;
		int layers = 3;
		float interp = mc.getRenderPartialTicks();

		float offset = mc.player.ticksExisted + interp;

		GlStateManager.disableLighting();
		GlStateManager.enableBlend();
		GlStateManager.color(1F, 1F, 1F, 1F);
		GlStateManager.depthFunc(GL11.GL_EQUAL);
		GlStateManager.depthMask(false);
		GlStateManager.blendFunc(GL11.GL_SRC_COLOR, GL11.GL_ONE);

		GlStateManager.matrixMode(GL11.GL_TEXTURE);
		GlStateManager.pushMatrix();

		for(int k = 0; k < layers; ++k) {
			GlStateManager.loadIdentity();
			float movement = offset * (0.001F + k * 0.003F) * speed;
			GlStateManager.scale(scale, scale, scale);
			GlStateManager.rotate(30.0F - k * 60.0F, 0.0F, 0.0F, 1.0F);
			GlStateManager.translate(0F, movement, 0F);
			GlStateManager.matrixMode(GL11.GL_MODELVIEW);
			GlStateManager.color(r * glintColor, g * glintColor, b * glintColor, 1.0F);
			ResourceManager.fatman.renderPart("MiniNuke");
			GlStateManager.matrixMode(GL11.GL_TEXTURE);
		}

		GlStateManager.popMatrix();
		GlStateManager.matrixMode(GL11.GL_MODELVIEW);

		// Restore to original state
		GlStateManager.color(1F, 1F, 1F, 1F);
		GlStateManager.depthMask(wasDepthMask);
		GlStateManager.depthFunc(prevDepthFunc);
		GlStateManager.tryBlendFuncSeparate(prevBlendSrc, prevBlendDst, prevBlendSrc, prevBlendDst);
		if(wasBlend) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
		if(wasLighting) GlStateManager.enableLighting(); else GlStateManager.disableLighting();

		GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
		if(wasLightmapOn) GlStateManager.enableTexture2D();
		GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
	}

	@Override
	protected void renderOther(ItemStack stack) {
		boolean wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
		int prevShadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL);

		GlStateManager.enableLighting();
		Minecraft.getMinecraft().getTextureManager().bindTexture(ResourceManager.fatman_tex);
		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		ResourceManager.fatman.renderPart("Launcher");
		ResourceManager.fatman.renderPart("Handle");
		ResourceManager.fatman.renderPart("Gauge");
		ResourceManager.fatman.renderPart("Lid");
		ResourceManager.fatman.renderPart("Piston");

		if(ItemGunBase.getMag(stack) > 0) {
			renderNuke(stack);
		}

		GlStateManager.shadeModel(prevShadeModel);
		if(!wasLighting) GlStateManager.disableLighting();
	}
}

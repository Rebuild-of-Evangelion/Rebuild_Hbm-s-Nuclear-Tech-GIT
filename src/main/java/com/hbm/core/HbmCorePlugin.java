package com.hbm.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import com.hbm.entity.effect.EntityNukeTorex;

import net.minecraft.block.material.MapColor;
import net.minecraft.entity.Entity;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.TransformerExclusions({"com.hbm.core"})
public class HbmCorePlugin implements IFMLLoadingPlugin, IClassTransformer {

	private static boolean runtimeDeobfEnabled = false;

	@Override
	public String[] getASMTransformerClass() {
		return new String[]{"com.hbm.core.HbmCorePlugin"};
	}

	public static boolean hasPermanentTag(Entity entity) {
		return entity.getEntityData().getBoolean("isPermanent");
	}

	/** Write fireMode and remap magazineType on the legacy gun tag. */
	public static void remapLegacyGunNbt(Object compound) {
		String id = readId(compound);
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		Object tag = getTag(compound);
		boolean changed = false;
		if(path.equals("gun_proto")) {
			setTagInt(tag, "fireMode", 1);
			changed = true;
		} else if(path.equals("gun_mirv") || path.equals("gun_bf")) {
			setTagInt(tag, "fireMode", 0);
			setTagInt(tag, "magazineType", getTagInt(tag, "magazineType") + (path.equals("gun_bf") ? 6 : 7));
			changed = true;
		}
		if(changed)
			putTag(compound, tag);
	}

	private static String readId(Object compound) {
		try {
			for(Method m : compound.getClass().getDeclaredMethods()) {
				if(m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == String.class && m.getReturnType() == String.class && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
					m.setAccessible(true);
					return (String) m.invoke(compound, "id");
				}
			}
		} catch(Exception e) { }
		return "";
	}

	private static Object getTag(Object compound) {
		try {
			for(Method m : compound.getClass().getDeclaredMethods()) {
				if(m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == String.class && m.getReturnType() == compound.getClass() && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
					m.setAccessible(true);
					return m.invoke(compound, "tag");
				}
			}
		} catch(Exception e) { }
		return null;
	}

	private static int getTagInt(Object tag, String key) {
		try {
			for(Method m : tag.getClass().getDeclaredMethods()) {
				if(m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == String.class && m.getReturnType() == int.class && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
					m.setAccessible(true);
					return ((Integer) m.invoke(tag, key)).intValue();
				}
			}
		} catch(Exception e) { }
		return 0;
	}

	private static void setTagInt(Object tag, String key, int value) {
		try {
			for(Method m : tag.getClass().getDeclaredMethods()) {
				if(m.getParameterTypes().length == 2 && m.getParameterTypes()[0] == String.class && m.getParameterTypes()[1] == int.class && m.getReturnType() == void.class && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
					m.setAccessible(true);
					m.invoke(tag, key, value);
					return;
				}
			}
		} catch(Exception e) { }
	}

	private static void putTag(Object compound, Object tag) {
		try {
			for(Method m : compound.getClass().getDeclaredMethods()) {
				if(m.getParameterTypes().length == 2 && m.getParameterTypes()[0] == String.class && m.getParameterTypes()[1].isAssignableFrom(tag.getClass()) && m.getReturnType() == void.class && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
					m.setAccessible(true);
					m.invoke(compound, "tag", tag);
					return;
				}
			}
		} catch(Exception e) { }
	}

	private static final java.util.Map<String, Integer> LEGACY_AMMO_META = new java.util.HashMap<>();
	static {
		LEGACY_AMMO_META.put("ammo_357_desh", 0);
		LEGACY_AMMO_META.put("ammo_4gauge_canister", 1);
		LEGACY_AMMO_META.put("ammo_4gauge_claw", 2);
		LEGACY_AMMO_META.put("ammo_4gauge_vampire", 3);
		LEGACY_AMMO_META.put("ammo_4gauge_void", 4);
		LEGACY_AMMO_META.put("ammo_5mm_chlorophyte", 5);
		LEGACY_AMMO_META.put("ammo_9mm_chlorophyte", 6);
		LEGACY_AMMO_META.put("ammo_556_chlorophyte", 7);
		LEGACY_AMMO_META.put("ammo_556_flechette_chlorophyte", 8);
		LEGACY_AMMO_META.put("ammo_50ae_chlorophyte", 9);
		LEGACY_AMMO_META.put("ammo_50bmg_chlorophyte", 10);
		LEGACY_AMMO_META.put("ammo_50bmg_flechette", 11);
		LEGACY_AMMO_META.put("ammo_50bmg_flechette_am", 12);
		LEGACY_AMMO_META.put("ammo_50bmg_flechette_po", 13);
		LEGACY_AMMO_META.put("ammo_44_chlorophyte", 14);
		LEGACY_AMMO_META.put("ammo_22lr_chlorophyte", 15);
		LEGACY_AMMO_META.put("ammo_rocket_canister", 16);
		LEGACY_AMMO_META.put("ammo_20gauge", 17);
		LEGACY_AMMO_META.put("ammo_20gauge_slug", 18);
		LEGACY_AMMO_META.put("ammo_20gauge_flechette", 19);
		LEGACY_AMMO_META.put("ammo_20gauge_incendiary", 20);
		LEGACY_AMMO_META.put("ammo_20gauge_explosive", 21);
		LEGACY_AMMO_META.put("ammo_20gauge_caustic", 22);
		LEGACY_AMMO_META.put("ammo_20gauge_shock", 23);
		LEGACY_AMMO_META.put("ammo_20gauge_wither", 24);
		LEGACY_AMMO_META.put("ammo_20gauge_sleek", 25);
		LEGACY_AMMO_META.put("ammo_20gauge_shrapnel", 26);
		LEGACY_AMMO_META.put("ammo_4gauge", 27);
		LEGACY_AMMO_META.put("ammo_4gauge_slug", 28);
		LEGACY_AMMO_META.put("ammo_4gauge_flechette", 29);
		LEGACY_AMMO_META.put("ammo_4gauge_flechette_phosphorus", 30);
		LEGACY_AMMO_META.put("ammo_4gauge_explosive", 31);
		LEGACY_AMMO_META.put("ammo_4gauge_semtex", 32);
		LEGACY_AMMO_META.put("ammo_4gauge_balefire", 33);
		LEGACY_AMMO_META.put("ammo_4gauge_kampf", 34);
		LEGACY_AMMO_META.put("ammo_4gauge_sleek", 35);
		LEGACY_AMMO_META.put("ammo_rocket", 36);
		LEGACY_AMMO_META.put("ammo_rocket_he", 37);
		LEGACY_AMMO_META.put("ammo_rocket_incendiary", 38);
		LEGACY_AMMO_META.put("ammo_rocket_phosphorus", 39);
		LEGACY_AMMO_META.put("ammo_rocket_shrapnel", 40);
		LEGACY_AMMO_META.put("ammo_rocket_emp", 41);
		LEGACY_AMMO_META.put("ammo_rocket_glare", 42);
		LEGACY_AMMO_META.put("ammo_rocket_toxic", 43);
		LEGACY_AMMO_META.put("ammo_rocket_sleek", 44);
		LEGACY_AMMO_META.put("ammo_rocket_nuclear", 45);
		LEGACY_AMMO_META.put("ammo_rocket_rpc", 46);
		LEGACY_AMMO_META.put("ammo_grenade", 47);
		LEGACY_AMMO_META.put("ammo_grenade_he", 48);
		LEGACY_AMMO_META.put("ammo_grenade_incendiary", 49);
		LEGACY_AMMO_META.put("ammo_grenade_phosphorus", 50);
		LEGACY_AMMO_META.put("ammo_grenade_toxic", 51);
		LEGACY_AMMO_META.put("ammo_grenade_concussion", 52);
		LEGACY_AMMO_META.put("ammo_grenade_finned", 53);
		LEGACY_AMMO_META.put("ammo_grenade_sleek", 54);
		LEGACY_AMMO_META.put("ammo_grenade_nuclear", 55);
		LEGACY_AMMO_META.put("ammo_grenade_tracer", 56);
		LEGACY_AMMO_META.put("ammo_grenade_kampf", 57);
		LEGACY_AMMO_META.put("ammo_shell", 58);
		LEGACY_AMMO_META.put("ammo_shell_explosive", 59);
		LEGACY_AMMO_META.put("ammo_shell_apfsds_t", 60);
		LEGACY_AMMO_META.put("ammo_shell_apfsds_du", 61);
		LEGACY_AMMO_META.put("ammo_shell_w9", 62);
		LEGACY_AMMO_META.put("ammo_dgk", 63);
		LEGACY_AMMO_META.put("ammo_nuke", 64);
		LEGACY_AMMO_META.put("ammo_nuke_safe", 65);
		LEGACY_AMMO_META.put("ammo_nuke_low", 66);
		LEGACY_AMMO_META.put("ammo_nuke_high", 67);
		LEGACY_AMMO_META.put("gun_bf_ammo", 68);
		LEGACY_AMMO_META.put("ammo_nuke_pumpkin", 69);
		LEGACY_AMMO_META.put("ammo_nuke_tots", 70);
		LEGACY_AMMO_META.put("ammo_mirv", 71);
		LEGACY_AMMO_META.put("ammo_mirv_safe", 72);
		LEGACY_AMMO_META.put("ammo_mirv_low", 73);
		LEGACY_AMMO_META.put("ammo_mirv_high", 74);
		LEGACY_AMMO_META.put("ammo_mirv_special", 75);
		LEGACY_AMMO_META.put("ammo_fuel", 76);
		LEGACY_AMMO_META.put("ammo_fuel_napalm", 77);
		LEGACY_AMMO_META.put("ammo_fuel_phosphorus", 78);
		LEGACY_AMMO_META.put("ammo_fuel_vaporizer", 79);
		LEGACY_AMMO_META.put("ammo_fuel_gas", 80);
		LEGACY_AMMO_META.put("ammo_dart", 81);
		LEGACY_AMMO_META.put("ammo_12gauge", 82);
		LEGACY_AMMO_META.put("ammo_12gauge_incendiary", 83);
		LEGACY_AMMO_META.put("ammo_12gauge_shrapnel", 84);
		LEGACY_AMMO_META.put("ammo_12gauge_du", 85);
		LEGACY_AMMO_META.put("ammo_12gauge_sleek", 86);
		LEGACY_AMMO_META.put("ammo_12gauge_marauder", 87);
		LEGACY_AMMO_META.put("ammo_22lr", 88);
		LEGACY_AMMO_META.put("ammo_22lr_ap", 89);
		LEGACY_AMMO_META.put("ammo_44", 90);
		LEGACY_AMMO_META.put("ammo_44_ap", 91);
		LEGACY_AMMO_META.put("ammo_44_du", 92);
		LEGACY_AMMO_META.put("ammo_44_phosphorus", 93);
		LEGACY_AMMO_META.put("ammo_44_pip", 94);
		LEGACY_AMMO_META.put("ammo_44_bj", 95);
		LEGACY_AMMO_META.put("ammo_44_silver", 96);
		LEGACY_AMMO_META.put("ammo_44_rocket", 97);
		LEGACY_AMMO_META.put("ammo_44_star", 98);
		LEGACY_AMMO_META.put("ammo_9mm", 99);
		LEGACY_AMMO_META.put("ammo_9mm_ap", 100);
		LEGACY_AMMO_META.put("ammo_9mm_du", 101);
		LEGACY_AMMO_META.put("ammo_9mm_rocket", 102);
		LEGACY_AMMO_META.put("ammo_556", 103);
		LEGACY_AMMO_META.put("ammo_556_phosphorus", 104);
		LEGACY_AMMO_META.put("ammo_556_ap", 105);
		LEGACY_AMMO_META.put("ammo_556_du", 106);
		LEGACY_AMMO_META.put("ammo_556_star", 107);
		LEGACY_AMMO_META.put("ammo_556_sleek", 108);
		LEGACY_AMMO_META.put("ammo_556_tracer", 109);
		LEGACY_AMMO_META.put("ammo_556_flechette", 110);
		LEGACY_AMMO_META.put("ammo_556_flechette_incendiary", 111);
		LEGACY_AMMO_META.put("ammo_556_flechette_phosphorus", 112);
		LEGACY_AMMO_META.put("ammo_556_flechette_du", 113);
		LEGACY_AMMO_META.put("ammo_556_flechette_sleek", 114);
		LEGACY_AMMO_META.put("ammo_556_k", 115);
		LEGACY_AMMO_META.put("ammo_50bmg", 116);
		LEGACY_AMMO_META.put("ammo_50bmg_incendiary", 117);
		LEGACY_AMMO_META.put("ammo_50bmg_phosphorus", 118);
		LEGACY_AMMO_META.put("ammo_50bmg_explosive", 119);
		LEGACY_AMMO_META.put("ammo_50bmg_du", 120);
		LEGACY_AMMO_META.put("ammo_50bmg_star", 121);
		LEGACY_AMMO_META.put("ammo_50bmg_sleek", 122);
		LEGACY_AMMO_META.put("ammo_75bolt", 123);
		LEGACY_AMMO_META.put("ammo_75bolt_incendiary", 124);
		LEGACY_AMMO_META.put("ammo_75bolt_he", 125);
		LEGACY_AMMO_META.put("ammo_50bmg_ap", 126);
		LEGACY_AMMO_META.put("ammo_5mm", 127);
		LEGACY_AMMO_META.put("ammo_5mm_explosive", 128);
		LEGACY_AMMO_META.put("ammo_5mm_du", 129);
		LEGACY_AMMO_META.put("ammo_5mm_star", 130);
		LEGACY_AMMO_META.put("ammo_50ae", 131);
		LEGACY_AMMO_META.put("ammo_50ae_ap", 132);
		LEGACY_AMMO_META.put("ammo_50ae_du", 133);
		LEGACY_AMMO_META.put("ammo_50ae_star", 134);
		LEGACY_AMMO_META.put("ammo_folly", 135);
		LEGACY_AMMO_META.put("ammo_folly_nuclear", 136);
		LEGACY_AMMO_META.put("ammo_folly_du", 137);
	}

	private static final java.util.Map<String, Integer> LEGACY_CLIP_META = new java.util.HashMap<>();
	static {
		LEGACY_CLIP_META.put("clip_revolver_iron", 0);
		LEGACY_CLIP_META.put("clip_revolver", 1);
		LEGACY_CLIP_META.put("clip_revolver_gold", 2);
		LEGACY_CLIP_META.put("clip_revolver_lead", 3);
		LEGACY_CLIP_META.put("clip_revolver_schrabidium", 4);
		LEGACY_CLIP_META.put("clip_revolver_cursed", 5);
		LEGACY_CLIP_META.put("clip_revolver_nightmare", 6);
		LEGACY_CLIP_META.put("clip_revolver_nightmare2", 7);
		LEGACY_CLIP_META.put("clip_revolver_pip", 8);
		LEGACY_CLIP_META.put("clip_revolver_nopip", 9);
		LEGACY_CLIP_META.put("clip_rpg", 10);
		LEGACY_CLIP_META.put("clip_stinger", 11);
		LEGACY_CLIP_META.put("clip_fatman", 12);
		LEGACY_CLIP_META.put("clip_mirv", 13);
		LEGACY_CLIP_META.put("clip_bf", 14);
		LEGACY_CLIP_META.put("clip_mp40", 15);
		LEGACY_CLIP_META.put("clip_uzi", 16);
		LEGACY_CLIP_META.put("clip_uboinik", 17);
		LEGACY_CLIP_META.put("clip_lever_action", 18);
		LEGACY_CLIP_META.put("clip_bolt_action", 19);
		LEGACY_CLIP_META.put("clip_osipr", 20);
		LEGACY_CLIP_META.put("clip_immolator", 21);
		LEGACY_CLIP_META.put("clip_cryolator", 22);
		LEGACY_CLIP_META.put("clip_mp", 23);
		LEGACY_CLIP_META.put("clip_xvl1456", 24);
		LEGACY_CLIP_META.put("clip_emp", 25);
		LEGACY_CLIP_META.put("clip_jack", 26);
		LEGACY_CLIP_META.put("clip_spark", 27);
		LEGACY_CLIP_META.put("clip_hp", 28);
		LEGACY_CLIP_META.put("clip_euthanasia", 29);
		LEGACY_CLIP_META.put("clip_defabricator", 30);
		LEGACY_CLIP_META.put("ammo_container", 31);
	}

	/** Legacy ammo/clip id -> meta ordinal, -1 if not a legacy item. */
	public static int legacyMetaFor(Object compound) {
		String id = readId(compound);
		if(id == null)
			return -1;
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		Integer meta = LEGACY_AMMO_META.get(path);
		if(meta != null)
			return meta.intValue();
		meta = LEGACY_CLIP_META.get(path);
		return meta == null ? -1 : meta.intValue();
	}

	/** Resolve a field name for runtime reflection: SRG name in production (obfuscated), MCP name in dev. */
	public static String chooseName(String mcp, String srg) {
		return runtimeDeobfEnabled ? srg : mcp;
	}

	@Override
	public byte[] transform(String name, String transformedName, byte[] basicClass) {
		if (transformedName.equals("net.minecraft.item.ItemStack"))
			return transformItemStack(basicClass);
		if (transformedName.equals("net.minecraft.world.World"))
			return transformWorld(basicClass);
		if (transformedName.equals("net.minecraft.client.renderer.EntityRenderer"))
			return transformEntityRenderer(basicClass);
		if (transformedName.equals("net.minecraft.item.ItemMap"))
			return transformItemMap(basicClass);
		if (transformedName.startsWith("net.minecraft.client.gui.MapItemRenderer"))
			return transformMapItemRenderer(basicClass);
		if (transformedName.equals("net.minecraft.block.material.MapColor"))
			return transformMapColor(basicClass);
		if (!transformedName.equals("net.minecraft.entity.passive.EntitySheep"))
			return basicClass;
		try {
			ClassNode classNode = new ClassNode();
			new ClassReader(basicClass).accept(classNode, 0);

			String className = classNode.name;
			String superName = classNode.superName;
			MethodNode eatGrassBonusNode = null;
			int setShearedIndex = -1;

			for (MethodNode method : classNode.methods) {
				if (eatGrassBonusNode == null && method.desc.equals("()V")) {
					for (int i = 0; i < method.instructions.size(); i++) {
						AbstractInsnNode insn = method.instructions.get(i);
						if (insn instanceof MethodInsnNode) {
							MethodInsnNode m = (MethodInsnNode) insn;
							if (m.desc.equals("(Z)V") && (m.owner.equals(className) || m.owner.equals(superName))) {
								eatGrassBonusNode = method;
								setShearedIndex = i;
								break;
							}
						}
					}
				}
			}

			if (eatGrassBonusNode == null)
				return basicClass;

			LabelNode skipLabel = new LabelNode();
			InsnList inject = new InsnList();
			inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
			inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
					Type.getInternalName(HbmCorePlugin.class), "hasPermanentTag",
					Type.getMethodDescriptor(Type.BOOLEAN_TYPE, Type.getType(Entity.class)), false));
			inject.add(new JumpInsnNode(Opcodes.IFNE, skipLabel));

			AbstractInsnNode targetInsn = eatGrassBonusNode.instructions.get(setShearedIndex);
			eatGrassBonusNode.instructions.insertBefore(eatGrassBonusNode.instructions.get(setShearedIndex - 2), inject);
			eatGrassBonusNode.instructions.insert(targetInsn, skipLabel);

			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
			classNode.accept(writer);
			return writer.toByteArray();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return basicClass;
	}

	/** Called from the patched World#getSkyColorBody tail. */
	public static Object getSkyFlashColor(double r, double g, double b) {
		float w = flashWeight();

		if(w <= 0F)
			return new Vec3d(r, g, b);

		return new Vec3d(
				r * (1.0D - w) + EntityNukeTorex.flashR * w,
				g * (1.0D - w) + EntityNukeTorex.flashG * w,
				b * (1.0D - w) + EntityNukeTorex.flashB * w);
	}

	/** Called from the patched EntityRenderer#updateLightmap loop body. */
	public static float getLightmapChannel(float base, float skyTerm, int lightIndex, int channel) {
		if(flashWeight() <= 0F)
			return base;

		float[] table = EntityNukeTorex.flashLightTable;

		if(table == null)
			return base;

		int index = lightIndex / 16;

		if(index < 0 || index >= table.length)
			return base;

		float f = EntityNukeTorex.flashI;
		float gap = table[index] - skyTerm;
		float brightened = gap > 0F ? base + gap * f : base;

		float luma = 0.299F * EntityNukeTorex.flashR + 0.587F * EntityNukeTorex.flashG + 0.114F * EntityNukeTorex.flashB;

		if(luma <= 0F)
			return brightened;

		float hue = channel == 0 ? EntityNukeTorex.flashR : channel == 1 ? EntityNukeTorex.flashG : EntityNukeTorex.flashB;
		return brightened * (1F + (hue / luma - 1F) * f);
	}

	private static float flashWeight() {
		return EntityNukeTorex.getFlashWeight();
	}

	/** Custom map colors: slot -> 0xRRGGBB, addressable through the unused byte range 208..255 (16 colors x 3 shades). */
	private static final int[] HBM_COLORS = new int[16];
	private static final IdentityHashMap<MapColor, Integer> HBM_SLOTS = new IdentityHashMap<>();
	private static int hbmNextSlot = 0;
	private static Constructor<MapColor> hbmCtor;

	/** MapColor constants handed to the fallout/radiation blocks; filled by registerMapColors(). */
	public static MapColor SELLAFIELD_SLAKED, SELLAFIELD_1, SELLAFIELD_2, SELLAFIELD_CORE, WASTE_GRASS, WASTE_MYCELIUM,
			WASTE_PODZOL, WASTE_WOOD, WASTE_TRINITITE, WASTE_TRINITITE_RED, WASTE_TALL_GRASS, WASTE_LEAVES, MUSH,
			MUSH_BLOCK, MUSH_BLOCK_RED, TOXIC_BLOCK;

	/** Fills the custom map color constants; called once from preInit on both sides. */
	public static void registerMapColors() {
		if(hbmNextSlot != 0)
			return;

		SELLAFIELD_SLAKED = mapColor(0x6F6F6F);
		SELLAFIELD_1 = mapColor(0x5F5F5F);
		SELLAFIELD_2 = mapColor(0x4C4C4C);
		SELLAFIELD_CORE = mapColor(0x2E2E2E);
		WASTE_GRASS = mapColor(0x403B2F);
		WASTE_MYCELIUM = mapColor(0x92E907);
		WASTE_PODZOL = mapColor(0x363328);
		WASTE_WOOD = mapColor(0x2D2D2D);
		WASTE_TRINITITE = mapColor(0xAFAE7E);
		WASTE_TRINITITE_RED = mapColor(0x8A5321);
		WASTE_TALL_GRASS = mapColor(0x877952);
		WASTE_LEAVES = mapColor(0x744A2F);
		MUSH = mapColor(0x9BF219);
		MUSH_BLOCK = mapColor(0x528D00);
		MUSH_BLOCK_RED = mapColor(0x6AB600);
		TOXIC_BLOCK = mapColor(0x202020);
	}

	/** Registers one custom map color; the returned instance is what Block#getMapColor should return. */
	public static MapColor mapColor(int rgb) {
		int slot = hbmNextSlot++;
		if(slot >= HBM_COLORS.length)
			throw new IllegalStateException("HBM map color slots exhausted");

		HBM_COLORS[slot] = rgb & 0xFFFFFF;

		MapColor color = newMapColor(52 + slot % 12, rgb & 0xFFFFFF);
		HBM_SLOTS.put(color, Integer.valueOf(slot));
		return color;
	}

	private static MapColor newMapColor(int index, int rgb) {
		try {
			if(hbmCtor == null) {
				for(Constructor<?> c : MapColor.class.getDeclaredConstructors()) {
					Class<?>[] p = c.getParameterTypes();
					if(p.length == 3 && p[0] == int.class && p[1] == int.class && p[2] == boolean.class) {
						hbmCtor = (Constructor<MapColor>) c;
						break;
					}
				}

				if(hbmCtor == null) {
					for(Constructor<?> c : MapColor.class.getDeclaredConstructors()) {
						Class<?>[] p = c.getParameterTypes();
						if(p.length == 2 && p[0] == int.class && p[1] == int.class) {
							hbmCtor = (Constructor<MapColor>) c;
							System.err.println("[HBM] MapColor ctor patch missing, custom map colors will occupy COLORS[52..63]");
							break;
						}
					}
				}
			}

			hbmCtor.setAccessible(true);

			if(hbmCtor.getParameterCount() == 3)
				return hbmCtor.newInstance(index, rgb, Boolean.FALSE);

			return hbmCtor.newInstance(index, rgb);
		} catch(Throwable t) {
			t.printStackTrace();
			return null;
		}
	}

	/** Write side: our instances map to 208 + slot*3 + shade, everything else keeps the vanilla formula. */
	public static byte enc(MapColor color, int shade) {
		if(color == null)
			return 0;

		Integer slot = HBM_SLOTS.get(color);

		if(slot != null) {
			int level = shade == 0 ? 0 : shade == 2 ? 2 : shade == 3 ? 0 : 1;
			return (byte)(208 + slot.intValue() * 3 + level);
		}

		return (byte)(color.colorIndex * 4 + shade);
	}

	/** Read side: byte values 208..255 carry a custom RGB plus one of the three vanilla shades. */
	public static int dec(int j) {
		if(j >= 208) {
			int idx = j - 208;
			int slot = idx / 3;
			int level = idx % 3;
			int factor = level == 0 ? 180 : level == 1 ? 220 : 255;
			int rgb = HBM_COLORS[slot];
			int r = ((rgb >> 16) & 255) * factor / 255;
			int g = ((rgb >> 8) & 255) * factor / 255;
			int b = (rgb & 255) * factor / 255;
			return 0xFF000000 | (r << 16) | (g << 8) | b;
		}
		return 0;
	}

	/** Next actual instruction, skipping labels and line numbers. */
	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode node = insn == null ? null : insn.getNext();

		while(node != null && node.getOpcode() < 0)
			node = node.getNext();

		return node;
	}

	/** Adds a MapColor ctor that sets the fields without touching COLORS, so custom colors occupy no palette slot. */
	private byte[] transformMapColor(byte[] basicClass) {
		try {
			ClassNode classNode = new ClassNode();
			new ClassReader(basicClass).accept(classNode, 0);

			FieldInsnNode colorIndex = null;
			FieldInsnNode colorValue = null;

			for(MethodNode method : classNode.methods) {
				if(!method.name.equals("<init>") || !method.desc.equals("(II)V"))
					continue;

				for(AbstractInsnNode insn : method.instructions.toArray()) {
					if(insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.PUTFIELD && ((FieldInsnNode) insn).desc.equals("I")) {
						if(colorIndex == null)
							colorIndex = (FieldInsnNode) insn;
						else if(colorValue == null)
							colorValue = (FieldInsnNode) insn;
					}
				}
			}

			if(colorIndex == null || colorValue == null)
				return basicClass;

			MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(IIZ)V", null, null);
			InsnList body = ctor.instructions;

			body.add(new VarInsnNode(Opcodes.ALOAD, 0));
			body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
			body.add(new VarInsnNode(Opcodes.ALOAD, 0));
			body.add(new VarInsnNode(Opcodes.ILOAD, 1));
			body.add(new FieldInsnNode(Opcodes.PUTFIELD, colorIndex.owner, colorIndex.name, colorIndex.desc));
			body.add(new VarInsnNode(Opcodes.ALOAD, 0));
			body.add(new VarInsnNode(Opcodes.ILOAD, 2));
			body.add(new FieldInsnNode(Opcodes.PUTFIELD, colorValue.owner, colorValue.name, colorValue.desc));
			body.add(new InsnNode(Opcodes.RETURN));

			classNode.methods.add(ctor);

			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
			classNode.accept(writer);
			return writer.toByteArray();
		} catch(Throwable t) {
			t.printStackTrace();
			return basicClass;
		}
	}

	/** Rewrites every "colorIndex * 4 + shade" map color write in ItemMap to go through enc(). */
	private byte[] transformItemMap(byte[] basicClass) {
		try {
			ClassNode classNode = new ClassNode();
			new ClassReader(basicClass).accept(classNode, 0);

			boolean patched = false;

			for(MethodNode method : classNode.methods) {
				List<Object[]> sites = new ArrayList<>();
				java.util.Set<String> staticDescs = new java.util.HashSet<>();

				for(AbstractInsnNode insn : method.instructions.toArray()) {
					if(insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.GETSTATIC)
						staticDescs.add(((FieldInsnNode) insn).desc);
				}

				for(AbstractInsnNode insn : method.instructions.toArray()) {
					if(!(insn instanceof FieldInsnNode))
						continue;

					FieldInsnNode f = (FieldInsnNode) insn;

					if(f.getOpcode() != Opcodes.GETFIELD || !f.desc.equals("I"))
						continue;

					// owner is not hardcoded: the same class must also be referenced by a GETSTATIC of type L<owner>;
					if(!staticDescs.contains("L" + f.owner + ";"))
						continue;

					AbstractInsnNode mul = next(f);
					AbstractInsnNode imul = next(mul);
					AbstractInsnNode shade = next(imul);
					AbstractInsnNode add = next(shade);
					AbstractInsnNode toByte = next(add);

					if(mul == null || mul.getOpcode() != Opcodes.ICONST_4)
						continue;
					if(imul == null || imul.getOpcode() != Opcodes.IMUL)
						continue;
					if(shade == null || shade.getOpcode() != Opcodes.ILOAD)
						continue;
					if(add == null || add.getOpcode() != Opcodes.IADD)
						continue;
					if(toByte == null || toByte.getOpcode() != Opcodes.I2B)
						continue;

					AbstractInsnNode color = prev(f);
					if(!(color instanceof VarInsnNode) || color.getOpcode() != Opcodes.ALOAD)
						continue;

					sites.add(new Object[] {color, toByte, color, shade, f.owner});
				}

				for(Object[] site : sites) {
					String mapColorType = (String) site[4];
					AbstractInsnNode from = (AbstractInsnNode) site[0];
					AbstractInsnNode to = (AbstractInsnNode) site[1];

					InsnList repl = new InsnList();
					repl.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode) site[2]).var));
					repl.add(new VarInsnNode(Opcodes.ILOAD, ((VarInsnNode) site[3]).var));
					repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(HbmCorePlugin.class), "enc",
							"(L" + mapColorType + ";I)B", false));

					method.instructions.insertBefore(from, repl);

					AbstractInsnNode node = from;
					while(node != null) {
						AbstractInsnNode n = node.getNext();
						boolean last = node == to;
						method.instructions.remove(node);
						if(last)
							break;
						node = n;
					}

					patched = true;
				}
			}

			if(!patched)
				return basicClass;

			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
			classNode.accept(writer);
			return writer.toByteArray();
		} catch(Throwable t) {
			t.printStackTrace();
			return basicClass;
		}
	}

	/** Makes MapItemRenderer consult dec() before the vanilla palette lookup. */
	private byte[] transformMapItemRenderer(byte[] basicClass) {
		try {
			ClassNode classNode = new ClassNode();
			new ClassReader(basicClass).accept(classNode, 0);

			boolean patched = false;

			for(MethodNode method : classNode.methods) {
				MethodInsnNode call = null;
				FieldInsnNode colors = null;

				// No hardcoded class name: the palette array's element type is the owner of getMapColor(int)
				for(AbstractInsnNode insn : method.instructions.toArray()) {
					if(!(insn instanceof FieldInsnNode))
						continue;

					FieldInsnNode f = (FieldInsnNode) insn;

					if(f.getOpcode() != Opcodes.GETSTATIC || !f.desc.startsWith("[L") || !f.desc.endsWith(";"))
						continue;

					String owner = f.desc.substring(2, f.desc.length() - 1);

					for(AbstractInsnNode n = f.getNext(); n != null; n = n.getNext()) {
						if(n instanceof MethodInsnNode) {
							MethodInsnNode m = (MethodInsnNode) n;
							if(m.getOpcode() == Opcodes.INVOKEVIRTUAL && m.owner.equals(owner) && m.desc.equals("(I)I")) {
								colors = f;
								call = m;
								break;
							}
						}
					}

					if(call != null)
						break;
				}

				if(call == null)
					continue;

				int indexVar = -1;

				for(AbstractInsnNode n = colors.getNext(); n != null && n != call; n = n.getNext()) {
					if(n.getOpcode() == Opcodes.ILOAD) {
						indexVar = ((VarInsnNode) n).var;
						break;
					}
				}

				if(indexVar < 0)
					continue;

				LabelNode done = new LabelNode();
				InsnList pre = new InsnList();
				pre.add(new VarInsnNode(Opcodes.ILOAD, indexVar));
				pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(HbmCorePlugin.class), "dec", "(I)I", false));
				pre.add(new InsnNode(Opcodes.DUP));
				pre.add(new JumpInsnNode(Opcodes.IFNE, done));
				pre.add(new InsnNode(Opcodes.POP));

				method.instructions.insertBefore(colors, pre);
				method.instructions.insert(call, done);

				patched = true;
			}

			if(!patched)
				return basicClass;

			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
			classNode.accept(writer);
			return writer.toByteArray();
		} catch(Throwable t) {
			t.printStackTrace();
			return basicClass;
		}
	}

	/** Instruction array without labels and line numbers. */
	private static AbstractInsnNode[] realInsns(MethodNode method) {
		List<AbstractInsnNode> list = new ArrayList<>();

		for(AbstractInsnNode insn : method.instructions.toArray()) {
			if(insn.getOpcode() >= 0)
				list.add(insn);
		}

		return list.toArray(new AbstractInsnNode[0]);
	}

	/** Previous node that is an actual instruction, skipping labels and line numbers. */
	private static AbstractInsnNode prev(AbstractInsnNode insn) {
		AbstractInsnNode node = insn == null ? null : insn.getPrevious();

		while(node != null && node.getOpcode() < 0)
			node = node.getPrevious();

		return node;
	}

	/** Replaces the Vec3d built at the end of World#getSkyColorBody with a colored variant. */
	private byte[] transformWorld(byte[] basicClass) {
		try {
			ClassNode classNode = new ClassNode();
			new ClassReader(basicClass).accept(classNode, 0);

			MethodNode target = null;

			for(MethodNode method : classNode.methods) {
				for(AbstractInsnNode insn : method.instructions.toArray()) {
					if(insn instanceof MethodInsnNode) {
						MethodInsnNode m = (MethodInsnNode) insn;
						if(m.owner.equals("net/minecraftforge/client/ForgeHooksClient") && m.name.equals("getSkyBlendColour")) {
							target = method;
							break;
						}
					}
				}
				if(target != null)
					break;
			}

			if(target == null) {
				return basicClass;
			}

			AbstractInsnNode ret = null;
			AbstractInsnNode start = null;
			AbstractInsnNode end = null;
			String vecName = null;
			int[] floats = new int[3];

			for(AbstractInsnNode insn : target.instructions.toArray()) {
				if(insn.getOpcode() != Opcodes.ARETURN)
					continue;

				AbstractInsnNode init = prev(insn);

				if(init == null || init.getOpcode() != Opcodes.INVOKESPECIAL || !(init instanceof MethodInsnNode) || !((MethodInsnNode) init).desc.equals("(DDD)V"))
					continue;

				AbstractInsnNode cur = init;
				boolean ok = true;

				for(int i = 2; i >= 0; i--) {
					AbstractInsnNode cast = prev(cur);
					AbstractInsnNode load = prev(cast);

					if(cast == null || cast.getOpcode() != Opcodes.F2D || load == null || load.getOpcode() != Opcodes.FLOAD) {
						ok = false;
						break;
					}

					floats[i] = ((VarInsnNode) load).var;
					cur = load;
				}

				if(!ok)
					continue;

				AbstractInsnNode dup = prev(cur);
				AbstractInsnNode created = prev(dup);

				if(dup == null || dup.getOpcode() != Opcodes.DUP || created == null || created.getOpcode() != Opcodes.NEW)
					continue;

				vecName = ((TypeInsnNode) created).desc;
				ret = insn;
				start = created;
				end = init;
				break;
			}

			if(ret == null) {
				return basicClass;
			}

			InsnList inject = new InsnList();

			for(int i = 0; i < 3; i++) {
				inject.add(new VarInsnNode(Opcodes.FLOAD, floats[i]));
				inject.add(new InsnNode(Opcodes.F2D));
			}

			inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(HbmCorePlugin.class), "getSkyFlashColor", "(DDD)Ljava/lang/Object;", false));
			inject.add(new TypeInsnNode(Opcodes.CHECKCAST, vecName));

			AbstractInsnNode node = start;

			while(node != null) {
				AbstractInsnNode next = node.getNext();
				if(node.getOpcode() >= 0)
					target.instructions.remove(node);
				if(node == end)
					break;
				node = next;
			}

			target.instructions.insertBefore(ret, inject);


			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			classNode.accept(writer);
			return writer.toByteArray();
		} catch(Exception e) {
			e.printStackTrace();
		}
		return basicClass;
	}

	/** Adds the colored flash to the lightmap entries built in EntityRenderer#updateLightmap. */
	private byte[] transformEntityRenderer(byte[] basicClass) {
		try {
			ClassNode classNode = new ClassNode();
			new ClassReader(basicClass).accept(classNode, 0);

			MethodNode target = null;
			int loopVar = -1;

			for(MethodNode method : classNode.methods) {
				AbstractInsnNode[] insns = realInsns(method);

				for(int i = 0; i + 4 < insns.length; i++) {
					if(insns[i].getOpcode() != Opcodes.INVOKEVIRTUAL || !(insns[i] instanceof MethodInsnNode))
						continue;

					if(!((MethodInsnNode) insns[i]).desc.equals("()[F"))
						continue;

					if(insns[i + 1].getOpcode() != Opcodes.ILOAD)
						continue;

					if(!(insns[i + 2] instanceof IntInsnNode) || insns[i + 2].getOpcode() != Opcodes.BIPUSH || ((IntInsnNode) insns[i + 2]).operand != 16)
						continue;

					if(insns[i + 3].getOpcode() != Opcodes.IDIV || insns[i + 4].getOpcode() != Opcodes.FALOAD)
						continue;

					target = method;
					loopVar = ((VarInsnNode) insns[i + 1]).var;
					break;
				}

				if(target != null)
					break;
			}

			if(target == null) {
				return basicClass;
			}

			AbstractInsnNode[] insns = realInsns(target);
			int[] storeVars = new int[3];
			int skyVar = -1;
			AbstractInsnNode insertAfter = null;
			int run = 0;

			for(int i = 0; i + 3 < insns.length; i++) {
				if(insns[i].getOpcode() == Opcodes.FLOAD && insns[i + 1].getOpcode() == Opcodes.FLOAD
						&& insns[i + 2].getOpcode() == Opcodes.FADD && insns[i + 3].getOpcode() == Opcodes.FSTORE) {
					run++;

					if(run <= 3)
						storeVars[run - 1] = ((VarInsnNode) insns[i + 3]).var;

					if(run == 3) {
						skyVar = ((VarInsnNode) insns[i]).var;
						insertAfter = insns[i + 3];
						break;
					}

					i += 3;
				} else {
					run = 0;
				}
			}

			if(insertAfter == null) {
				return basicClass;
			}

			InsnList inject = new InsnList();

			for(int channel = 0; channel < 3; channel++) {
				inject.add(new VarInsnNode(Opcodes.FLOAD, storeVars[channel]));
				inject.add(new VarInsnNode(Opcodes.FLOAD, skyVar));
				inject.add(new VarInsnNode(Opcodes.ILOAD, loopVar));
				inject.add(new InsnNode(Opcodes.ICONST_0 + channel));
				inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(HbmCorePlugin.class), "getLightmapChannel", "(FFII)F", false));
				inject.add(new VarInsnNode(Opcodes.FSTORE, storeVars[channel]));
			}

			target.instructions.insert(insertAfter, inject);


			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			classNode.accept(writer);
			return writer.toByteArray();
		} catch(Exception e) {
			e.printStackTrace();
		}
		return basicClass;
	}

	private byte[] transformItemStack(byte[] basicClass) {
		try {
			ClassNode classNode = new ClassNode();
			new ClassReader(basicClass).accept(classNode, 0);

			for (MethodNode method : classNode.methods) {
				if (!method.name.equals("<init>"))
					continue;
				boolean hasGetString = false;
				for (AbstractInsnNode insn : method.instructions.toArray()) {
					if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).desc.equals("(Ljava/lang/String;)Ljava/lang/String;")) {
						hasGetString = true;
						break;
					}
				}
				if (!hasGetString)
					continue;
				FieldInsnNode putDamageField = null;
				int intPuts = 0;
				for (AbstractInsnNode insn : method.instructions.toArray()) {
					if (insn instanceof FieldInsnNode) {
						FieldInsnNode f = (FieldInsnNode) insn;
						if (f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(classNode.name) && f.desc.equals("I")) {
							intPuts++;
							if (intPuts == 2) {
								putDamageField = f;
								break;
							}
						}
					}
				}
				if (putDamageField == null)
					return basicClass;

				LabelNode skipMeta = new LabelNode();
				LabelNode endMeta = new LabelNode();
				InsnList inject = new InsnList();
				inject.add(new VarInsnNode(Opcodes.ALOAD, 1));
				inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(HbmCorePlugin.class), "remapLegacyGunNbt", "(Ljava/lang/Object;)V", false));
				inject.add(new VarInsnNode(Opcodes.ALOAD, 1));
				inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(HbmCorePlugin.class), "legacyMetaFor", "(Ljava/lang/Object;)I", false));
				inject.add(new InsnNode(Opcodes.DUP));
				inject.add(new JumpInsnNode(Opcodes.IFLT, skipMeta));
				inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
				inject.add(new InsnNode(Opcodes.SWAP));
				inject.add(new FieldInsnNode(Opcodes.PUTFIELD, putDamageField.owner, putDamageField.name, putDamageField.desc));
				inject.add(new JumpInsnNode(Opcodes.GOTO, endMeta));
				inject.add(skipMeta);
				inject.add(new InsnNode(Opcodes.POP));
				inject.add(endMeta);

				method.instructions.insert(putDamageField.getNext(), inject);
			}

			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
			classNode.accept(writer);
			return writer.toByteArray();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return basicClass;
	}

	@Override
	public String getModContainerClass() {
		return "com.hbm.core.HbmCoreModContainer";
	}

	@Override
	public String getSetupClass() {
		return null;
	}

	@Override
	public void injectData(Map<String, Object> data) {
		runtimeDeobfEnabled = (Boolean) data.get("runtimeDeobfuscationEnabled");
	}

	@Override
	public String getAccessTransformerClass() {
		return null;
	}
}
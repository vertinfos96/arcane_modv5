package arcane.entity;

import arcane.block.ModBlocks;
import arcane.data.ArcaneConfig;
import arcane.data.ClassAbilities;
import arcane.data.Fx;
import arcane.item.ModItems;
import arcane.particle.ModParticles;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Арканный Страж — босс.
 * Бой в стиле «замах — предупреждение на земле — удар»: перед каждой атакой босс замирает, поднимает клинок,
 * на земле вспыхивает зона удара, и через ~0,7 секунды атака срабатывает. Успей отойти или перекатиться (X).
 * Атаки: Сокрушающий круг, Рывок, Веер клинка, Метеоритный дождь, Звездопад. Призывает големов.
 */
public class ArcaneWardenEntity extends ZombieEntity {

	private static final int SLAM = 0;
	private static final int CHARGE = 1;
	private static final int SWEEP = 2;
	private static final int METEORS = 3;
	private static final int STARFALL = 4;
	/** Замах каждой атаки в тиках (20 тиков = 1 секунда). */
	private static final int[] WINDUP = {16, 14, 12, 20, 18};

	private static final double SLAM_RADIUS = 6.5;
	private static final double CHARGE_LENGTH = 14.0;
	private static final double SWEEP_RADIUS = 7.5;

	private final ServerBossBar bossBar = new ServerBossBar(
			Text.translatable("entity.arcane.arcane_warden"), BossBar.Color.PURPLE, BossBar.Style.NOTCHED_10);

	private int stage = 0;
	private int cooldown = 80;
	private int castLeft = 0;
	private int attack = -1;
	private Vec3d aimDir = new Vec3d(0.0, 0.0, 1.0);
	private final List<Vec3d> spots = new ArrayList<>();
	private boolean announced = false;

	public ArcaneWardenEntity(EntityType<? extends ZombieEntity> type, World world) {
		super(type, world);
		this.setPersistent();
		// Броня и корона нарисованы на текстуре, в руке — Клинок Стража.
		this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(ModItems.WARDEN_BLADE));
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return ZombieEntity.createZombieAttributes()
				.add(EntityAttributes.MAX_HEALTH, 300.0)
				.add(EntityAttributes.ATTACK_DAMAGE, 14.0)
				.add(EntityAttributes.MOVEMENT_SPEED, 0.27)
				.add(EntityAttributes.ARMOR, 10.0)
				.add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(EntityAttributes.FOLLOW_RANGE, 40.0)
				.add(EntityAttributes.SCALE, 1.8);
	}

	// ------------------------------------------------------------------
	// Главный цикл
	// ------------------------------------------------------------------

	@Override
	public void tick() {
		super.tick();
		if (!(this.getWorld() instanceof ServerWorld world)) {
			return;
		}
		bossBar.setPercent(this.getHealth() / this.getMaxHealth());
		aura(world);
		if (this.age % 40 == 0 && !this.getMainHandStack().isOf(ModItems.WARDEN_BLADE)) {
			this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(ModItems.WARDEN_BLADE));
		}
		if (!announced && this.age > 5) {
			announced = true;
			announce(world);
		}
		if (!this.isAlive()) {
			return;
		}

		LivingEntity target = this.getTarget();
		if (castLeft > 0) {
			tickCast(world, target);
			return;
		}
		if (target == null) {
			return;
		}

		float ratio = this.getHealth() / this.getMaxHealth();
		if (ratio < 0.66f && stage < 1) {
			stage = 1;
			summonGolems(world, 2);
		}
		if (ratio < 0.33f && stage < 2) {
			stage = 2;
			summonGolems(world, 3);
		}

		if (cooldown > 0) {
			cooldown--;
			return;
		}
		if (this.squaredDistanceTo(target) > 45.0 * 45.0) {
			return;
		}
		startAttack(world, target);
	}

	private void startAttack(ServerWorld world, LivingEntity target) {
		double dist = Math.sqrt(this.squaredDistanceTo(target));
		// Порядок: круг, рывок, веер, метеоры, звездопад.
		int[] weights = dist < 6.0 ? new int[]{35, 10, 30, 15, 10}
				: dist < 14.0 ? new int[]{15, 30, 5, 30, 20}
				: new int[]{0, 30, 0, 40, 30};
		int total = 0;
		for (int weight : weights) {
			total += weight;
		}
		int roll = this.random.nextInt(total);
		int pick = METEORS;
		for (int i = 0; i < weights.length; i++) {
			roll -= weights[i];
			if (roll < 0) {
				pick = i;
				break;
			}
		}
		if (pick == attack && weights[(pick + 1) % 5] > 0) {
			pick = (pick + 1) % 5; // не повторяем одну атаку подряд
		}
		attack = pick;
		castLeft = WINDUP[pick];

		Vec3d to = target.getPos().subtract(this.getPos()).multiply(1.0, 0.0, 1.0);
		aimDir = to.lengthSquared() < 0.01 ? new Vec3d(0.0, 0.0, 1.0) : to.normalize();

		spots.clear();
		if (pick == STARFALL) {
			int n = stage >= 2 ? 5 : 3;
			for (int i = 0; i < n; i++) {
				double a = this.random.nextDouble() * Math.PI * 2.0;
				double r = i == 0 ? 0.0 : 3.0 + this.random.nextDouble() * 3.0;
				Vec3d spot = target.getPos().add(Math.cos(a) * r, 0.0, Math.sin(a) * r);
				spots.add(new Vec3d(spot.x, floorY(world, spot.x, spot.y, spot.z), spot.z));
			}
		}

		this.setAiDisabled(true);
		this.setGlowing(true);
		this.swingHand(Hand.MAIN_HAND);
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.squaredDistanceTo(this) < 50.0 * 50.0) {
				p.sendMessage(Text.translatable("boss.arcane.attack." + pick), true);
			}
		}
		cue(world, pick);
	}

	private void cue(ServerWorld world, int pick) {
		BlockPos pos = this.getBlockPos();
		switch (pick) {
			case SLAM -> world.playSound(null, pos, SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 1.5f, 0.5f);
			case CHARGE -> world.playSound(null, pos, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.HOSTILE, 1.5f, 0.6f);
			case SWEEP -> world.playSound(null, pos, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 1.5f, 0.5f);
			case METEORS -> world.playSound(null, pos, SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, SoundCategory.HOSTILE, 1.8f, 0.6f);
			default -> world.playSound(null, pos, SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, SoundCategory.HOSTILE, 1.5f, 1.0f);
		}
	}

	private void tickCast(ServerWorld world, LivingEntity target) {
		castLeft--;
		this.getNavigation().stop();
		if (target != null && attack != CHARGE && attack != SWEEP) {
			this.lookAtEntity(target, 40.0f, 40.0f);
		}
		double progress = 1.0 - Math.max(0, castLeft) / (double) WINDUP[attack];
		telegraph(world, progress);
		if (castLeft <= 0) {
			execute(world);
			this.setAiDisabled(false);
			this.setGlowing(false);
			cooldown = (stage >= 2 ? 30 : 50) + (attack == METEORS ? (stage >= 2 ? 14 : 9) * 3 : 0);
		}
	}

	// ------------------------------------------------------------------
	// Предупреждения на земле
	// ------------------------------------------------------------------

	private void telegraph(ServerWorld world, double p) {
		Vec3d c = this.getPos().add(0.0, 0.12, 0.0);
		boolean outerTick = this.age % 3 == 0;
		boolean innerTick = this.age % 2 == 0;
		switch (attack) {
			case SLAM -> {
				if (outerTick) {
					Fx.circle(world, c, SLAM_RADIUS, ModParticles.RUNE, 26);
				}
				if (innerTick) {
					Fx.circle(world, c, SLAM_RADIUS * p, ModParticles.GOLD_DUST, 14);
				}
			}
			case CHARGE -> {
				Vec3d end = c.add(aimDir.multiply(CHARGE_LENGTH));
				Vec3d side = new Vec3d(-aimDir.z, 0.0, aimDir.x).multiply(1.4);
				if (outerTick) {
					Fx.line(world, c, end, ModParticles.RUNE, 1.1);
					Fx.line(world, c.add(side), end.add(side), ModParticles.RUNE, 1.8);
					Fx.line(world, c.subtract(side), end.subtract(side), ModParticles.RUNE, 1.8);
				}
				if (innerTick) {
					Fx.line(world, c, c.add(aimDir.multiply(CHARGE_LENGTH * p)), ModParticles.GOLD_DUST, 1.0);
				}
			}
			case SWEEP -> {
				if (outerTick) {
					for (int a = -60; a <= 60; a += 10) {
						Vec3d d = aimDir.rotateY((float) Math.toRadians(a)).multiply(SWEEP_RADIUS);
						world.spawnParticles(ModParticles.RUNE, c.x + d.x, c.y, c.z + d.z, 1, 0.0, 0.0, 0.0, 0.0);
					}
					Fx.line(world, c, c.add(aimDir.rotateY((float) Math.toRadians(60)).multiply(SWEEP_RADIUS)), ModParticles.RUNE, 1.3);
					Fx.line(world, c, c.add(aimDir.rotateY((float) Math.toRadians(-60)).multiply(SWEEP_RADIUS)), ModParticles.RUNE, 1.3);
				}
				if (innerTick) {
					for (int a = -60; a <= 60; a += 15) {
						Vec3d d = aimDir.rotateY((float) Math.toRadians(a)).multiply(SWEEP_RADIUS * p);
						world.spawnParticles(ModParticles.GOLD_DUST, c.x + d.x, c.y, c.z + d.z, 1, 0.0, 0.0, 0.0, 0.0);
					}
				}
			}
			case METEORS -> {
				world.spawnParticles(ModParticles.GOLD_DUST, this.getX(), this.getY() + 4.6, this.getZ(), 6, 1.2, 0.7, 1.2, 0.05);
				world.spawnParticles(ModParticles.ARCANE_SPARK, this.getX(), this.getY() + 5.0, this.getZ(), 1, 0.8, 0.4, 0.8, 0.02);
				if (this.age % 4 == 0) {
					Fx.circle(world, c, 12.0, ModParticles.RUNE, 34);
				}
			}
			default -> {
				for (Vec3d spot : spots) {
					if (outerTick) {
						Fx.circle(world, spot.add(0.0, 0.12, 0.0), 2.6, ModParticles.RUNE, 20);
					}
					if (innerTick) {
						Fx.circle(world, spot.add(0.0, 0.12, 0.0), 2.6 * p, ModParticles.GOLD_DUST, 10);
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Удары
	// ------------------------------------------------------------------

	private void execute(ServerWorld world) {
		switch (attack) {
			case SLAM -> doSlam(world);
			case CHARGE -> doCharge(world);
			case SWEEP -> doSweep(world);
			case METEORS -> launchMeteors(world);
			default -> doStarfall(world);
		}
	}

	private List<PlayerEntity> playersNear(ServerWorld world, Vec3d pos, double radius) {
		List<PlayerEntity> list = new ArrayList<>();
		for (PlayerEntity p : world.getEntitiesByClass(PlayerEntity.class,
				new Box(pos, pos).expand(radius, 4.0, radius), pl -> pl.isAlive() && !pl.isSpectator())) {
			double dx = p.getX() - pos.x;
			double dz = p.getZ() - pos.z;
			if (dx * dx + dz * dz <= radius * radius) {
				list.add(p);
			}
		}
		return list;
	}

	private void hit(ServerWorld world, PlayerEntity p, float damage, double knockback, double fromX, double fromZ) {
		p.damage(world, world.getDamageSources().mobAttack(this), damage);
		p.takeKnockback(knockback, fromX - p.getX(), fromZ - p.getZ());
	}

	private void doSlam(ServerWorld world) {
		for (PlayerEntity p : playersNear(world, this.getPos(), SLAM_RADIUS)) {
			hit(world, p, 14.0f, 1.5, this.getX(), this.getZ());
		}
		Vec3d c = this.getPos().add(0.0, 0.15, 0.0);
		for (int k = 0; k < 6; k++) {
			final int step = k;
			ClassAbilities.later(k, () -> {
				Fx.circle(world, c, 1.0 + step * 1.1, ModParticles.GOLD_DUST, 12 + step * 6);
				Fx.circle(world, c, 1.0 + step * 1.1, ParticleTypes.CLOUD, 8 + step * 3);
			});
		}
		world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 0.3, c.z, 1, 0.0, 0.0, 0.0, 0.0);
		world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.HOSTILE, 1.5f, 0.7f);
	}

	private void doCharge(ServerWorld world) {
		Vec3d start = this.getPos();
		Vec3d end = start;
		for (double t = 1.0; t <= CHARGE_LENGTH; t += 1.0) {
			Vec3d p = start.add(aimDir.multiply(t));
			BlockPos bp = BlockPos.ofFloored(p);
			if (!passable(world, bp) || !passable(world, bp.up())) {
				break;
			}
			end = p;
		}
		for (PlayerEntity p : world.getEntitiesByClass(PlayerEntity.class, new Box(start, end).expand(2.5, 3.0, 2.5),
				pl -> pl.isAlive() && !pl.isSpectator())) {
			if (segmentDistance(p.getPos(), start, end) <= 2.1) {
				hit(world, p, 18.0f, 1.4, start.x, start.z);
			}
		}
		Fx.line(world, start.add(0.0, 1.0, 0.0), end.add(0.0, 1.0, 0.0), ParticleTypes.SWEEP_ATTACK, 1.4);
		Fx.line(world, start.add(0.0, 0.4, 0.0), end.add(0.0, 0.4, 0.0), ModParticles.GOLD_DUST, 0.7);
		this.refreshPositionAfterTeleport(end.x, end.y, end.z);
		world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.HOSTILE, 1.6f, 0.5f);
		world.spawnParticles(ParticleTypes.EXPLOSION, end.x, end.y + 0.5, end.z, 1, 0.0, 0.0, 0.0, 0.0);
	}

	private void doSweep(ServerWorld world) {
		Vec3d c = this.getPos();
		for (PlayerEntity p : playersNear(world, c, SWEEP_RADIUS)) {
			Vec3d to = p.getPos().subtract(c).multiply(1.0, 0.0, 1.0);
			if (to.lengthSquared() < 0.01 || aimDir.dotProduct(to.normalize()) >= 0.5) {
				hit(world, p, 13.0f, 1.3, c.x, c.z);
			}
		}
		for (int a = -60; a <= 60; a += 10) {
			Vec3d d = aimDir.rotateY((float) Math.toRadians(a));
			for (double r = 1.5; r <= SWEEP_RADIUS; r += 1.5) {
				world.spawnParticles(ParticleTypes.SWEEP_ATTACK, c.x + d.x * r, c.y + 1.0, c.z + d.z * r, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
		world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 1.8f, 0.6f);
	}

	private void doStarfall(ServerWorld world) {
		for (Vec3d spot : new ArrayList<>(spots)) {
			LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world, SpawnReason.TRIGGERED);
			if (bolt != null) {
				bolt.setPosition(spot);
				bolt.setCosmetic(true);
				world.spawnEntity(bolt);
			}
			for (PlayerEntity p : playersNear(world, spot, 2.6)) {
				hit(world, p, 12.0f, 0.6, spot.x, spot.z);
			}
			world.spawnParticles(ModParticles.STORM_SPARK, spot.x, spot.y + 0.6, spot.z, 30, 1.0, 1.4, 1.0, 0.25);
			Fx.circle(world, spot.add(0.0, 0.2, 0.0), 2.0, ModParticles.GOLD_DUST, 16);
		}
	}

	// ------------------------------------------------------------------
	// Метеоритный дождь
	// ------------------------------------------------------------------

	private void launchMeteors(ServerWorld world) {
		int count = stage >= 2 ? 14 : 9;
		Vec3d center = this.getPos();
		LivingEntity target = this.getTarget();
		for (int i = 0; i < count; i++) {
			double a = this.random.nextDouble() * Math.PI * 2.0;
			double r;
			Vec3d base;
			if (i < 2 && target != null) {
				// два метеора рядом с игроком (не точно в него — только рядом)
				base = target.getPos();
				r = 2.8 + this.random.nextDouble() * 1.2;
			} else {
				base = center;
				r = 3.5 + this.random.nextDouble() * 8.5;
			}
			double x = base.x + Math.cos(a) * r;
			double z = base.z + Math.sin(a) * r;
			Vec3d spot = new Vec3d(x, floorY(world, x, center.y, z), z);
			scheduleMeteor(world, spot, i * 3);
		}
	}

	private void scheduleMeteor(ServerWorld world, Vec3d spot, int delay) {
		Vec3d from = spot.add(7.0, 22.0, 4.0);
		for (int t = 0; t < 14; t++) {
			final int tt = t;
			ClassAbilities.later(delay + t + 1, () -> {
				double k = tt / 13.0;
				Vec3d p = from.add(spot.subtract(from).multiply(k));
				world.spawnParticles(ModParticles.METEOR_FLAME, p.x, p.y, p.z, 5, 0.3, 0.3, 0.3, 0.02);
				world.spawnParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, 2, 0.25, 0.25, 0.25, 0.01);
				if (tt % 2 == 0) {
					Fx.circle(world, spot.add(0.0, 0.15, 0.0), 3.4, ModParticles.RUNE, 18);
					Fx.circle(world, spot.add(0.0, 0.15, 0.0), 3.4 * k, ModParticles.GOLD_DUST, 12);
				}
				if (tt == 0) {
					world.playSound(null, BlockPos.ofFloored(spot), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.HOSTILE, 1.6f, 0.5f);
				}
			});
		}
		ClassAbilities.later(delay + 15, () -> meteorImpact(world, spot));
	}

	private void meteorImpact(ServerWorld world, Vec3d pos) {
		world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y + 0.5, pos.z, 1, 0.0, 0.0, 0.0, 0.0);
		world.spawnParticles(ModParticles.METEOR_FLAME, pos.x, pos.y + 0.6, pos.z, 50, 1.6, 1.0, 1.6, 0.15);
		world.spawnParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 0.6, pos.z, 30, 1.4, 1.0, 1.4, 0.08);
		for (int k = 0; k < 4; k++) {
			final int step = k;
			ClassAbilities.later(k, () -> Fx.circle(world, pos.add(0.0, 0.3, 0.0), 1.2 + step * 1.1, ModParticles.GOLD_DUST, 14 + step * 6));
		}
		world.playSound(null, BlockPos.ofFloored(pos), SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.HOSTILE, 2.0f, 0.8f);
		for (PlayerEntity p : playersNear(world, pos, 3.6)) {
			hit(world, p, 11.0f, 1.0, pos.x, pos.z);
		}
		if (ArcaneConfig.bossTerrainDamage) {
			crater(world, pos, 2.7);
		}
	}

	/** Воронка как от TNT: убирает блоки в шаре (без выпадения предметов, алтарь не трогает). */
	private static void crater(ServerWorld world, Vec3d c, double radius) {
		int ri = (int) Math.ceil(radius);
		for (int dx = -ri; dx <= ri; dx++) {
			for (int dy = -ri; dy <= ri; dy++) {
				for (int dz = -ri; dz <= ri; dz++) {
					if (dx * dx + dy * dy + dz * dz > radius * radius) {
						continue;
					}
					BlockPos bp = BlockPos.ofFloored(c.x + dx, c.y - 0.6 + dy, c.z + dz);
					BlockState state = world.getBlockState(bp);
					if (state.isAir() || !state.getFluidState().isEmpty() || state.isOf(ModBlocks.ARCANE_ALTAR)) {
						continue;
					}
					if (state.getHardness(world, bp) < 0.0f) {
						continue;
					}
					world.removeBlock(bp, false);
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Помощники
	// ------------------------------------------------------------------

	private static boolean passable(ServerWorld world, BlockPos pos) {
		return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
	}

	/** Высота пола рядом с заданной точкой (в пределах от +3 до -6 блоков). */
	private static double floorY(ServerWorld world, double x, double y, double z) {
		for (int dy = 3; dy >= -6; dy--) {
			BlockPos p = BlockPos.ofFloored(x, y + dy, z);
			if (passable(world, p) && !passable(world, p.down())) {
				return p.getY();
			}
		}
		return y;
	}

	private static double segmentDistance(Vec3d p, Vec3d a, Vec3d b) {
		Vec3d pp = new Vec3d(p.x, 0.0, p.z);
		Vec3d aa = new Vec3d(a.x, 0.0, a.z);
		Vec3d ab = new Vec3d(b.x, 0.0, b.z).subtract(aa);
		double len2 = ab.lengthSquared();
		if (len2 < 1.0E-6) {
			return pp.distanceTo(aa);
		}
		double t = Math.max(0.0, Math.min(1.0, pp.subtract(aa).dotProduct(ab) / len2));
		return pp.distanceTo(aa.add(ab.multiply(t)));
	}

	private void summonGolems(ServerWorld world, int count) {
		for (int i = 0; i < count; i++) {
			CrystalGolemEntity golem = ModEntities.CRYSTAL_GOLEM.create(world, SpawnReason.MOB_SUMMONED);
			if (golem != null) {
				double angle = this.random.nextDouble() * Math.PI * 2.0;
				golem.setPosition(this.getX() + Math.cos(angle) * 4.0, this.getY(), this.getZ() + Math.sin(angle) * 4.0);
				world.spawnEntity(golem);
			}
		}
		for (int k = 0; k < 5; k++) {
			final int step = k;
			ClassAbilities.later(k * 2, () -> Fx.circle(world, this.getPos().add(0.0, 0.2, 0.0), 1.5 + step * 1.6, ModParticles.STORM_SPARK, 20 + step * 6));
		}
		world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 1.0f, 1.2f);
	}

	/** Крупный заголовок при появлении босса. */
	private void announce(ServerWorld world) {
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.squaredDistanceTo(this) < 80.0 * 80.0) {
				p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 50, 20));
				p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.translatable("boss.arcane.subtitle")));
				p.networkHandler.sendPacket(new TitleS2CPacket(Text.translatable("entity.arcane.arcane_warden")));
			}
		}
	}

	/**
	 * Аура: два вращающихся в разные стороны кольца рун на земле, четыре золотых осколка,
	 * летающих по орбитам разной высоты, и искры над короной.
	 */
	private void aura(ServerWorld world) {
		double t = this.age * 0.05;
		Vec3d c = this.getPos();
		if (this.age % 4 == 0) {
			for (int i = 0; i < 12; i++) {
				double a = t * 1.3 + i * Math.PI * 2.0 / 12.0;
				world.spawnParticles(ModParticles.RUNE, c.x + Math.cos(a) * 2.9, c.y + 0.1, c.z + Math.sin(a) * 2.9, 1, 0.0, 0.0, 0.0, 0.0);
			}
			for (int i = 0; i < 8; i++) {
				double a = -t * 1.9 + i * Math.PI * 2.0 / 8.0;
				world.spawnParticles(ModParticles.GOLD_DUST, c.x + Math.cos(a) * 1.8, c.y + 0.1, c.z + Math.sin(a) * 1.8, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
		if (this.age % 2 == 0) {
			for (int k = 0; k < 4; k++) {
				double a = t * 2.4 + k * Math.PI / 2.0;
				double r = 2.3 + 0.35 * Math.sin(t * 3.0 + k);
				double y = 1.6 + 1.0 * Math.sin(t * 2.0 + k * 1.7);
				world.spawnParticles(ModParticles.GOLD_DUST, c.x + Math.cos(a) * r, c.y + y, c.z + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
		if (this.age % 5 == 0) {
			world.spawnParticles(ModParticles.ARCANE_SPARK, c.x, c.y + 3.6, c.z, 1, 0.45, 0.1, 0.45, 0.01);
		}
	}

	@Override
	public void onDeath(DamageSource damageSource) {
		super.onDeath(damageSource);
		if (this.getWorld() instanceof ServerWorld world) {
			Vec3d c = this.getPos();
			for (int k = 0; k < 10; k++) {
				final int step = k;
				ClassAbilities.later(k * 2, () -> {
					Fx.circle(world, c.add(0.0, 0.2 + step * 0.3, 0.0), 1.0 + step * 0.7, ModParticles.GOLD_DUST, 24);
					world.spawnParticles(ModParticles.GOLD_DUST, c.x, c.y + 1.5 + step * 0.3, c.z, 12, 1.2, 0.8, 1.2, 0.2);
				});
			}
			for (int i = 0; i < 3; i++) {
				LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world, SpawnReason.TRIGGERED);
				if (bolt != null) {
					double a = i * Math.PI * 2.0 / 3.0;
					bolt.setPosition(c.x + Math.cos(a) * 4.0, c.y, c.z + Math.sin(a) * 4.0);
					bolt.setCosmetic(true);
					world.spawnEntity(bolt);
				}
			}
			world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 1.5f, 0.6f);
		}
	}

	@Override
	public void onStartedTrackingBy(ServerPlayerEntity player) {
		super.onStartedTrackingBy(player);
		bossBar.addPlayer(player);
	}

	@Override
	public void onStoppedTrackingBy(ServerPlayerEntity player) {
		super.onStoppedTrackingBy(player);
		bossBar.removePlayer(player);
	}

	@Override
	public void remove(Entity.RemovalReason reason) {
		super.remove(reason);
		bossBar.clearPlayers();
	}

	@Override
	public boolean isBaby() {
		return false;
	}

	@Override
	protected boolean burnsInDaylight() {
		return false;
	}

	@Override
	protected boolean canConvertInWater() {
		return false;
	}
}

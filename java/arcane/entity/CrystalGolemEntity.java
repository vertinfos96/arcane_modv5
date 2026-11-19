package arcane.entity;

import arcane.data.ClassAbilities;
import arcane.data.Fx;
import arcane.particle.ModParticles;
import arcane.item.ModItems;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Кристальный голем: крепкий враг с кристальной булавой.
 * Способности: «Сокрушающий удар» по площади и «Кристальный панцирь» (стойкость при малом здоровье).
 */
public class CrystalGolemEntity extends ZombieEntity {

	private boolean shelled = false;

	public CrystalGolemEntity(EntityType<? extends ZombieEntity> type, World world) {
		super(type, world);
		this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(ModItems.CRYSTAL_MACE));
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return ZombieEntity.createZombieAttributes()
				.add(EntityAttributes.MAX_HEALTH, 40.0)
				.add(EntityAttributes.ATTACK_DAMAGE, 6.0)
				.add(EntityAttributes.MOVEMENT_SPEED, 0.25)
				.add(EntityAttributes.KNOCKBACK_RESISTANCE, 0.5);
	}

	@Override
	public void tick() {
		super.tick();
		if (!(this.getWorld() instanceof ServerWorld world)) {
			return;
		}
		// Всегда держим свою булаву (ваниль иногда выдаёт зомби железный меч).
		if (this.age % 40 == 0 && !this.getMainHandStack().isOf(ModItems.CRYSTAL_MACE)) {
			this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(ModItems.CRYSTAL_MACE));
		}
		// Мерцание кристаллов.
		if (this.age % 6 == 0) {
			world.spawnParticles(ModParticles.ARCANE_SPARK, this.getX(), this.getY() + 1.0, this.getZ(), 2, 0.35, 0.7, 0.35, 0.01);
			if (this.age % 24 == 0) {
				world.spawnParticles(ModParticles.RUNE, this.getX(), this.getY() + 1.6, this.getZ(), 6, 0.4, 0.4, 0.4, 0.3);
			}
		}

		LivingEntity target = this.getTarget();
		if (target == null || !this.isAlive()) {
			return;
		}
		// Сокрушающий удар: раз в 7 секунд, если цель рядом.
		if (this.age % 140 == 0 && this.squaredDistanceTo(target) < 36.0) {
			slam(world);
		}
		// Кристальный всплеск: волна кристаллов вырывается из земли в сторону цели.
		double dist = Math.sqrt(this.squaredDistanceTo(target));
		if (this.age % 100 == 50 && dist > 3.0 && dist < 14.0) {
			eruption(world, target);
		}
		// Кристальный панцирь: при здоровье ниже 50% — один раз получает стойкость.
		if (!shelled && this.getHealth() < this.getMaxHealth() * 0.5f) {
			shelled = true;
			this.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 300, 1));
			Fx.spiral(world, this.getPos(), 1.0, 2.4, 3.0, 40, ModParticles.ARCANE_SPARK);
			world.playSound(null, this.getBlockPos(), SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.HOSTILE, 1.5f, 0.6f);
		}
	}

	/** Волна кристаллов: 7 всплесков по прямой, один за другим (можно перепрыгнуть/уйти вбок). */
	private void eruption(ServerWorld world, LivingEntity target) {
		Vec3d start = this.getPos();
		Vec3d to = target.getPos().subtract(start).multiply(1.0, 0.0, 1.0);
		if (to.lengthSquared() < 0.01) {
			return;
		}
		Vec3d dir = to.normalize();
		List<PlayerEntity> struck = new ArrayList<>();
		for (int i = 1; i <= 7; i++) {
			final int step = i;
			ClassAbilities.later(i * 2, () -> {
				Vec3d p = start.add(dir.multiply(step * 1.6));
				world.spawnParticles(ModParticles.CRYSTAL_GLINT, p.x, p.y + 0.6, p.z, 8, 0.25, 0.5, 0.25, 0.05);
				world.spawnParticles(ModParticles.ARCANE_SPARK, p.x, p.y + 1.0, p.z, 2, 0.2, 0.6, 0.2, 0.02);
				world.playSound(null, BlockPos.ofFloored(p), SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK,
						SoundCategory.HOSTILE, 0.9f, 0.8f + step * 0.05f);
				for (PlayerEntity pl : world.getEntitiesByClass(PlayerEntity.class, new Box(p, p).expand(1.3, 2.0, 1.3),
						x -> x.isAlive() && !x.isSpectator())) {
					if (!struck.contains(pl)) {
						struck.add(pl);
						pl.damage(world, world.getDamageSources().mobAttack(this), 7.0f);
						pl.takeKnockback(0.8, p.x - pl.getX(), p.z - pl.getZ());
					}
				}
			});
		}
	}

	/** При смерти голем разлетается кристальными осколками, которые ранят всех рядом. */
	@Override
	public void onDeath(DamageSource damageSource) {
		super.onDeath(damageSource);
		if (!(this.getWorld() instanceof ServerWorld world)) {
			return;
		}
		Vec3d c = this.getPos().add(0.0, 1.0, 0.0);
		List<PlayerEntity> struck = new ArrayList<>();
		for (int i = 0; i < 14; i++) {
			double a = i * Math.PI * 2.0 / 14.0 + this.random.nextDouble() * 0.3;
			Vec3d dir = new Vec3d(Math.cos(a), 0.15 + this.random.nextDouble() * 0.35, Math.sin(a)).normalize();
			for (int s = 1; s <= 8; s++) {
				final int st = s;
				ClassAbilities.later(st, () -> {
					Vec3d p = c.add(dir.multiply(st * 0.8));
					world.spawnParticles(ModParticles.CRYSTAL_GLINT, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.0);
					for (PlayerEntity pl : world.getEntitiesByClass(PlayerEntity.class, new Box(p, p).expand(1.1, 1.5, 1.1),
							x -> x.isAlive() && !x.isSpectator())) {
						if (!struck.contains(pl)) {
							struck.add(pl);
							pl.damage(world, world.getDamageSources().mobAttack(this), 6.0f);
							pl.takeKnockback(0.9, c.x - pl.getX(), c.z - pl.getZ());
						}
					}
				});
			}
		}
		world.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 1, 0.0, 0.0, 0.0, 0.0);
		Fx.circle(world, this.getPos().add(0.0, 0.2, 0.0), 1.5, ModParticles.CRYSTAL_GLINT, 16);
		world.playSound(null, this.getBlockPos(), SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.HOSTILE, 1.6f, 0.5f);
		world.playSound(null, this.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 1.2f, 0.7f);
	}

	private void slam(ServerWorld world) {
		for (PlayerEntity p : world.getEntitiesByClass(PlayerEntity.class, this.getBoundingBox().expand(4.5),
				pl -> pl.isAlive() && !pl.isSpectator())) {
			p.damage(world, world.getDamageSources().mobAttack(this), 7.0f);
			p.takeKnockback(1.0, this.getX() - p.getX(), this.getZ() - p.getZ());
		}
		Vec3d c = this.getPos().add(0.0, 0.15, 0.0);
		for (int i = 1; i <= 4; i++) {
			Fx.circle(world, c, i * 1.1, ModParticles.CRYSTAL_GLINT, 12 + i * 6);
		}
		world.spawnParticles(ModParticles.ARCANE_SPARK, c.x, c.y + 0.3, c.z, 30, 1.5, 0.2, 1.5, 0.15);
		world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 1.2f, 0.7f);
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

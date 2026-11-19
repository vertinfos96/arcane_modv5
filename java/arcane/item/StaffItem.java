package arcane.item;

import arcane.data.ArcaneData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/**
 * Волшебный посох.
 * ПКМ — применить выбранное заклинание, клавиша Z (или R) с посохом в руке — переключить заклинание.
 */
public class StaffItem extends Item {

	public StaffItem(Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult use(World world, PlayerEntity user, Hand hand) {
		if (world.isClient) {
			return ActionResult.SUCCESS;
		}
		ServerPlayerEntity player = (ServerPlayerEntity) user;
		ServerWorld serverWorld = (ServerWorld) world;
		ArcaneData data = ArcaneData.get(player);
		ItemStack stack = user.getStackInHand(hand);

		int spell = data.hasSpell(data.selected()) ? data.selected() : 0;
		boolean ok = Spells.cast(player, serverWorld, spell, data);
		if (ok) {
			int ticks = (int) Math.max(5, Spells.baseCooldown(spell) * data.cooldownMultiplier());
			player.getItemCooldownManager().set(stack, ticks);
		}
		return ActionResult.SUCCESS;
	}
}

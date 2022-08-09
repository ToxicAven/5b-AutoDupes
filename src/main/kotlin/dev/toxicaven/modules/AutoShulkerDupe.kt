package dev.toxicaven.modules

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.mixin.extension.rightClickDelayTimer
import com.lambda.client.module.Category
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.util.items.blockBlacklist
import com.lambda.client.util.items.shulkerList
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.runSafeR
import com.lambda.client.util.threads.safeListener
import dev.toxicaven.DupePlugin
import net.minecraft.block.Block
import net.minecraft.block.BlockAir
import net.minecraft.block.BlockHopper
import net.minecraft.block.BlockShulkerBox
import net.minecraft.block.state.IBlockState
import net.minecraft.client.gui.inventory.GuiCrafting
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.inventory.ClickType
import net.minecraft.item.Item
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemStack
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.util.EnumActionResult
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraftforge.fml.common.gameevent.TickEvent


/*
 * @author Techale
 * Modified for lambda by ToxicAven
 */

internal object AutoShulkerDupe: PluginModule(
    name = "AutoShulkerDupe",
    category = Category.MISC,
    description = "Dupe shulkers on 5b5t",
    pluginMain = DupePlugin
) {
    private var hopperCheck by setting("Hopper Check", false)
    private var itemCrafting by setting("Item Crafting", 60, 0..100, 1)
    private var maxStackWait by setting("Max Stack Wait", 60, 0..100, 1)
    private var waitPlace by setting("Wait Place", 60, 0..100, 1)
    private var instructions by setting("Instructions", false)
    private val confirm by setting("Confirm", false)

    private var shulkerPos: BlockPos? = null
    private var wbPos:BlockPos? = null
    private var slotPick = 0
    private var slotShulk = 0
    private var stage = 0
    private var slotWood = 0
    private var tickPutItem = 0
    private var beforePlaced = false

    init {
        onEnable {
            if (!confirm) {
                MessageSendHelper.sendChatMessage("This dupe method is designed for when 5b5t did not allow stacked" +
                    " shulkers, and should only be used over AutoItemDupe if this has happened again.")
                MessageSendHelper.sendChatMessage("If you would like to use it anyway, please enabled the confirm" +
                    " option.")
                disable()
            }

            if (instructions) {
                MessageSendHelper.sendChatMessage("To do the dupe, stand on a crafting table that is flush against " +
                    "the ground, look straight down, and enable the module. You need a shulker in your hand, a " +
                    "pickaxe in your hotbar, and wood planks in your inventory.")
                instructions = false
            }
            runSafeR {
                initValues()
            }
        }

        safeListener<TickEvent.ClientTickEvent> {
            when (stage) {
                0 -> {
                    // Check if the slot is not empty
                    if (player.inventory.getStackInSlot(slotShulk + 36).isEmpty) {
                        // If it is, look for another shulker
                        slotShulk = findFirstShulker()
                        if (slotShulk == -1) {
                            // If not found, disable
                            disable()
                        }
                    }
                    // Drop the shulker
                    playerController.windowClick(0, slotShulk + 36, 0, ClickType.THROW, player)
                    if (player.isSneaking) player.connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.STOP_SNEAKING))
                    // Right-Click the wb
                    wbPos?.let {
                        playerController.processRightClickBlock(player, world, it, EnumFacing.UP, Vec3d(it), EnumHand.MAIN_HAND)
                    }
                    if (player.isSneaking) player.connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_SNEAKING))
                    // Go to the other stage
                    stage = 1
                    tickPutItem = 0
                }
                1 ->
                    // If we are in the wb
                    if (mc.currentScreen is GuiCrafting) {
                        // Wait for crafting
                        if (tickPutItem++ >= itemCrafting) {
                            // We split the wood and take it
                            playerController.windowClick(player.openContainer.windowId, if (slotWood < 9) slotWood + 37 else slotWood + 1, 1, ClickType.PICKUP, mc.player)
                            // And then put on the wb
                            playerController.windowClick(player.openContainer.windowId, 1, 0, ClickType.PICKUP, player)
                            // Update controller for wb output
                            playerController.updateController()
                            // Next stage
                            stage = 2
                            tickPutItem = 0
                        }
                    }
                2 -> {
                    // Iterate whole hotbar
                    var i = 0
                    while (i < 9) {
                        // If it's block, and shulker, and it is > 1
                        if (player.inventory.getStackInSlot(i).item is ItemBlock
                            && (player.inventory.getStackInSlot(i).item as ItemBlock).block is BlockShulkerBox && player.inventory.getStackInSlot(i).count > 1) {
                            // Close and place it
                            player.closeScreen()
                            player.inventory.currentItem = i
                            if (!player.isSneaking) player.connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_SNEAKING))
                            shulkerPos?.let { it1 -> place(it1, EnumHand.MAIN_HAND, false) }
                            if (!player.isSneaking) player.connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.STOP_SNEAKING))
                            // Ready for the other stage
                            stage = 3
                            tickPutItem = 0
                            beforePlaced = false
                            break
                        }
                        i++
                    }
                    // In case of error, and it has not found a shulker
                    if (tickPutItem++ > maxStackWait) {
                        stage = 0
                        player.closeScreen()
                        tickPutItem = 0
                    }
                }
                3 ->
                    // If that block is BlockShulker
                    if (getBlock(shulkerPos) is BlockShulkerBox) {
                        // Continue mining it
                        beforePlaced = true
                        player.inventory.currentItem = slotPick
                        player.swingArm(EnumHand.MAIN_HAND)
                        shulkerPos?.let { playerController.onPlayerDamageBlock(it, EnumFacing.UP) }
                    } else {
                        // If beforePlaced == true and this is not blockShulker (so we have mined it)
                        // Or we run out of time
                        if (beforePlaced || tickPutItem++ > waitPlace) stage = 0
                    }
            }
        }
    }

    private fun SafeClientEvent.initValues() {
        // Check for the crafting table
        wbPos = BlockPos(player.posX, player.posY, player.posZ).add(.5, -1.0, .5)
        // Check for the hopper
        shulkerPos = null
        for (surround in arrayOf( // -2 Because the hopper must be down
            Vec3d(1.0, -2.0, 0.0),
            Vec3d(-1.0, -2.0, 0.0),
            Vec3d(0.0, -2.0, 1.0),
            Vec3d(0.0, -2.0, -1.0)
        )) {
            // If we have to check for a hopper
            if (hopperCheck) {
                // Pos hopper
                val pos = BlockPos(player.posX + surround.x, player.posY + surround.y, player.posZ + surround.z)
                // Is hopper
                if (getBlock(pos) is BlockHopper) {
                    shulkerPos = BlockPos(player.posX + surround.x, player.posY, player.posZ + surround.z)
                    break
                }
            } else {
                // Else, the block must be air
                val pos = BlockPos(player.posX + surround.x, player.posY, player.posZ + surround.z)
                if (getBlock(pos) is BlockAir) {
                    shulkerPos = pos
                    break
                }
            }
        }
        if (shulkerPos == null) {
            disable()
            return
        }
        slotPick = findFirstItemSlot(Items.DIAMOND_PICKAXE)
        if (slotPick == -1) {
            disable()
            return
        }
        slotWood = findFirstBlockSlot(Blocks.PLANKS)
        if (slotWood == -1) {
            disable()
            return
        }
        slotShulk = findFirstShulker()
        if (slotShulk == -1) {
            disable()
            return
        }
        stage = 0
    }

    private fun SafeClientEvent.findFirstShulker(): Int {
        var slot = -1
        val mainInventory: List<ItemStack> = player.inventory.mainInventory
        for (i in 0..8) {
            val stack = mainInventory[i]
            if (stack == ItemStack.EMPTY || stack.item !is ItemBlock) continue
            if ((stack.item as ItemBlock).block is BlockShulkerBox) {
                slot = i
                break
            }
        }
        return slot
    }

    private fun SafeClientEvent.findFirstItemSlot(itemToFind: Item): Int {
        var slot = -1
        val mainInventory: List<ItemStack> = player.inventory.mainInventory
        for (i in 0..8) {
            val stack = mainInventory[i]
            if (stack == ItemStack.EMPTY || itemToFind != stack.item) {
                continue
            }
            if (itemToFind == stack.item) {
                slot = i
                break
            }
        }
        return slot
    }

    private fun SafeClientEvent.findFirstBlockSlot(blockToFind: Block): Int {
        var slot = -1
        val mainInventory: List<ItemStack> = player.inventory.mainInventory
        for (i in 0..35) {
            val stack = mainInventory[i]
            if (stack == ItemStack.EMPTY || stack.item !is ItemBlock) {
                continue
            }
            if (blockToFind == (stack.item as ItemBlock).block) {
                slot = i
                break
            }
        }
        return slot
    }

    private fun SafeClientEvent.getBlock(pos: BlockPos?): Block? {
        return getState(pos)?.block
    }

    private fun SafeClientEvent.getState(pos: BlockPos?): IBlockState? {
        return mc.world.getBlockState(pos)
    }

    private fun SafeClientEvent.canBeClicked(pos: BlockPos?): Boolean {
        return getBlock(pos)!!.canCollideCheck(getState(pos), false)
    }

    private fun SafeClientEvent.place(blockPos: BlockPos, hand: EnumHand?, rotate: Boolean): Boolean {
        return placeBlock(blockPos, hand, rotate, null)
    }

    private fun SafeClientEvent.placeBlock(blockPos: BlockPos, hand: EnumHand?, checkAction: Boolean, forceSide: ArrayList<EnumFacing?>?): Boolean {
        if (!world.getBlockState(blockPos).material.isReplaceable) {
            return false
        }
        val side: EnumFacing = (if (forceSide != null) placeableSideExclude(blockPos, forceSide) else getPlaceableSide(blockPos))
            ?: return false
        val neighbour = blockPos.offset(side)
        val opposite = side.opposite
        if (!canBeClicked(neighbour)) {
            return false
        }
        val hitVec = Vec3d(neighbour).add(0.5, 0.5, 0.5).add(Vec3d(opposite.directionVec).scale(0.5))
        val neighbourBlock = world.getBlockState(neighbour).block
        if (!player.isSneaking && blockBlacklist.contains(neighbourBlock) || shulkerList.contains(neighbourBlock)) {
            player.connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_SNEAKING))
        }
        val action = playerController.processRightClickBlock(player, world, neighbour, opposite, hitVec, hand)
        if (!checkAction || action == EnumActionResult.SUCCESS) {
            player.swingArm(hand)
            mc.rightClickDelayTimer = 4
        }
        return action == EnumActionResult.SUCCESS
    }

    private fun SafeClientEvent.getPlaceableSide(pos: BlockPos): EnumFacing? {
        for (side in EnumFacing.values()) {
            val neighbour = pos.offset(side)
            if (!world.getBlockState(neighbour).block.canCollideCheck(world.getBlockState(neighbour), false)) {
                continue
            }
            val blockState = world.getBlockState(neighbour)
            if (!blockState.material.isReplaceable) {
                return side
            }
        }
        return null
    }

    private fun SafeClientEvent.placeableSideExclude(pos: BlockPos, excluding: ArrayList<EnumFacing?>): EnumFacing? {
        for (side in EnumFacing.values()) {
            if (!excluding.contains(side)) {
                val neighbour = pos.offset(side)
                if (!world.getBlockState(neighbour).block.canCollideCheck(world.getBlockState(neighbour), false)) {
                    continue
                }
                val blockState = world.getBlockState(neighbour)
                if (!blockState.material.isReplaceable) {
                    return side
                }
            }
        }
        return null
    }

    override fun getHudInfo(): String {
        return when (stage) {
            0 -> "Dropping shulker"
            1 -> "Crafting button"
            2 -> "Waiting on the shulker"
            3 -> "Breaking the shulker"
            else -> ""
        }
    }
}
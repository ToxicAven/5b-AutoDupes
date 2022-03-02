package dev.toxicaven.modules

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.manager.managers.PlayerPacketManager.sendPlayerPacket
import com.lambda.client.module.Category
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.util.math.Vec2f
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.runSafe
import com.lambda.client.util.threads.runSafeR
import com.lambda.client.util.threads.safeListener
import dev.toxicaven.DupePlugin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.inventory.GuiInventory
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Blocks
import net.minecraft.inventory.ClickType
import net.minecraft.item.Item
import net.minecraft.item.ItemBlock
import net.minecraft.item.crafting.CraftingManager
import net.minecraft.item.crafting.IRecipe
import net.minecraft.network.play.client.CPacketPlaceRecipe
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.common.gameevent.TickEvent

/*
 * @author ToxicAven
 */

internal object AutoItemDupe: PluginModule(
    name = "AutoItemDupe",
    category = Category.MISC,
    description = "Dupe items on 5b5t",
    pluginMain = DupePlugin
) {
    private val cancelGUI by setting("Cancel GUI", true)
    private val interacting by setting("Rotation Mode", RotationMode.SPOOF, description = "Force view client side, only server side or no interaction at all")
    private var instructions by setting("Instructions", true)
    private val confirm by setting("Confirm", false)

    @Suppress("UNUSED")
    enum class RotationMode {
        OFF, SPOOF, VIEW_LOCK
    }

    private var currentWaitPhase = WaitPhase.NONE
    private var startTimeStamp = 0L
    private var countBefore = 0
    private var idBefore = 0
    private var slotBefore = 0
    private var lastClickStamp = System.currentTimeMillis()
    private var recipeLocation: ResourceLocation = ResourceLocation("wooden_button")
    //The only way this would be null is if your game is *FUCKED*
    private var pktRecipe: IRecipe = CraftingManager.REGISTRY.getObject(recipeLocation)!!

    init {
        onEnable {
            runSafeR {
                val check = checkForPlanks()
                if (check == -1) abort("Planks were not found in inventory.") else currentWaitPhase = WaitPhase.DROP

                if (instructions) {
                    MessageSendHelper.sendChatMessage("To do the dupe, have wooden planks in your inventory, hold the item you wish to dupe, and toggle the module. wait until the item is picked back up.")
                    MessageSendHelper.sendWarningMessage("If 5b5t has disabled stacked shulkers, use 'AutoShulkerDupe' for those.")
                    instructions = false
                }
            } ?: disable()
        }

        onDisable {
            if (cancelGUI) mc.displayGuiScreen(null)
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (currentWaitPhase == WaitPhase.NONE) disable()

            if (currentWaitPhase == WaitPhase.DROP) {

                if (player.inventory.getCurrentItem().isEmpty) abort("You need to hold an item.")

                if (System.currentTimeMillis() - startTimeStamp < 120L) {
                    if (!player.recipeBook.isGuiOpen) player.recipeBook.isGuiOpen = true
                }

                updateRotation()

                idBefore = Item.getIdFromItem(player.inventory.getCurrentItem().item)
                countBefore = countItem(idBefore)
                slotBefore = player.inventory.currentItem
                throwAllInSlot(slotBefore + 36)

                if (!cancelGUI) mc.displayGuiScreen(GuiInventory(player as EntityPlayer) as GuiScreen)
                if (!player.recipeBook.isGuiOpen) abort("Failed to open Recipe Book. Try opening it manually.")

                currentWaitPhase = WaitPhase.PICKUP
            }

            else if (currentWaitPhase == WaitPhase.PICKUP) {
                if (System.currentTimeMillis() - lastClickStamp < 300L) disable()
                lastClickStamp = System.currentTimeMillis()
            }
        }
    }

    private fun SafeClientEvent.updateRotation() {
        when (interacting) {
            RotationMode.SPOOF -> {
                sendPlayerPacket {
                    rotate(Vec2f(player.rotationYaw, 180f))
                }
            }
            RotationMode.VIEW_LOCK -> {
                player.rotationPitch = 180f
            }
            else -> {
                // RotationMode.OFF
            }
        }
    }

    private fun SafeClientEvent.countItem(itemId: Int): Int {
        val itemList: ArrayList<Int> = getSlots(itemId)
        var currentCount = 0
        val iterator: Iterator<Int> = itemList.iterator()
        while (iterator.hasNext()) {
            val i = iterator.next()
            currentCount += player.inventory.getStackInSlot(i).count
        }
        return currentCount
    }

    private fun SafeClientEvent.getSlots(itemID: Int): ArrayList<Int> {
        val slots = ArrayList<Int>()
        for (i in 0..8) {
            if (Item.getIdFromItem(player.inventory.getStackInSlot(i).item) == itemID) slots.add(Integer.valueOf(i))
        }
        return slots
    }

    private fun throwAllInSlot(slot: Int) {
        defaultScope.launch {
            runSafe {
                playerController.windowClick(player.inventoryContainer.windowId, slot, 1, ClickType.THROW, player)
            }
            delay(1000L)
            runSafe {
                connection.sendPacket(CPacketPlaceRecipe(0, pktRecipe, false))
            }
        }
    }

    private fun SafeClientEvent.checkForPlanks(): Int {
        for (i in 0..35) {
            val stack = player.inventory.getStackInSlot(i)
            if (stack.item is ItemBlock) {
                val block = (stack.item as ItemBlock).block
                if (block == Blocks.PLANKS) {
                    return i
                }
            }
        }
        return -1
    }

    private fun abort(msg: String) {
        MessageSendHelper.sendErrorMessage(msg)
        currentWaitPhase = WaitPhase.NONE //this acts as 'disableclass'
        mc.displayGuiScreen(null)
    }

    enum class WaitPhase {
        NONE, DROP, PICKUP
    }
}
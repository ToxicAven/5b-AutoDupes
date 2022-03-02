package dev.toxicaven

import com.lambda.client.plugin.api.Plugin
import dev.toxicaven.modules.AutoItemDupe
import dev.toxicaven.modules.AutoShulkerDupe

internal object DupePlugin : Plugin() {

    override fun onLoad() {
        // Load any modules, commands, or HUD elements here
        modules.add(AutoItemDupe)
        modules.add(AutoShulkerDupe)
    }

    override fun onUnload() {
        // Here you can unregister threads etc...
    }
}
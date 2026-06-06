/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
 * Copyright (C) 2023 CinemaMod Group
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */

package net.ccbluex.liquidbounce.mcef.mixin;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.vulkan.MCEFVulkanDeviceExtensions;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

/**
 * Enables Vulkan external-memory device extensions for MCEF when they are supported by the selected GPU.
 * <p>
 * The extensions are appended at logical device creation time instead of added to Minecraft's required
 * extension set. This keeps Minecraft's Vulkan backend usable on devices that cannot support accelerated
 * browser texture import.
 */
@NullMarked
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {

    @Inject(
            method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("HEAD")
    )
    private static void mcef$enableOptionalExternalMemoryExtensions(
            Collection<String> deviceExtensions,
            VulkanPhysicalDevice physicalDevice,
            Set<VulkanFeature> vulkanFeatures,
            CallbackInfoReturnable<VkDevice> ci
    ) {
        var enabledExtensions = new ArrayList<String>();
        for (var extension : MCEFVulkanDeviceExtensions.OPTIONAL_EXTERNAL_MEMORY_IMPORT) {
            if (physicalDevice.hasDeviceExtension(extension) && deviceExtensions.add(extension)) {
                enabledExtensions.add(extension);
            }
        }

        if (!enabledExtensions.isEmpty()) {
            MCEF.INSTANCE.LOGGER.info(
                    "Enabled optional Vulkan external-memory extensions for MCEF: {}",
                    enabledExtensions
            );
        }
    }

}

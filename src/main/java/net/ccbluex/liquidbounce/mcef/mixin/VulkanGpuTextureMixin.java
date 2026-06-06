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

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import net.ccbluex.liquidbounce.mcef.vulkan.MCEFVulkanExternalTexture;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.util.vma.Vma;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

/**
 * Adds an external-image ownership mode to Minecraft's Vulkan texture wrapper.
 * <p>
 * MCEF's Vulkan accelerated paint path needs to present short-lived images imported from CEF platform
 * handles as normal Blaze3D textures so they can be copied into an MCEF-owned display texture. Vanilla
 * {@link VulkanGpuTexture} always destroys its image through VMA, which is not valid for images imported
 * from D3D11 shared handles, dmabufs, or other external-memory paths. This mixin lets those callers replace
 * the backing handles and provide the matching destroy action.
 */
@NullMarked
@Mixin(VulkanGpuTexture.class)
public abstract class VulkanGpuTextureMixin implements MCEFVulkanExternalTexture {

    @Shadow
    @Final
    private VulkanDevice device;

    @Shadow
    @Final
    @Mutable
    private long vkImage;

    @Shadow
    @Final
    @Mutable
    private long vmaAllocation;

    @Shadow
    public abstract boolean isClosed();

    @Unique
    private boolean mcef$externalVkImage;

    @Unique
    private boolean mcef$externalVkImageDestroyed;

    @Unique
    private @Nullable Runnable mcef$externalDestroyAction;

    @Override
    public void mcef$setExternalVkImage(long vkImage, long vmaAllocation, Runnable destroyAction) {
        if (vkImage == 0L) {
            throw new IllegalArgumentException("External Vulkan image must not be VK_NULL_HANDLE");
        }

        if (isClosed()) {
            throw new IllegalStateException("Cannot replace the Vulkan image of a closed texture");
        }

        Objects.requireNonNull(destroyAction, "destroyAction");

        mcef$queueCurrentImageForDestroy();

        this.vkImage = vkImage;
        this.vmaAllocation = vmaAllocation;
        this.mcef$externalVkImage = true;
        this.mcef$externalVkImageDestroyed = false;
        this.mcef$externalDestroyAction = destroyAction;
    }

    @Override
    public boolean mcef$isExternalVkImage() {
        return mcef$externalVkImage;
    }

    @Override
    public long mcef$getVkImage() {
        return vkImage;
    }

    @Override
    public long mcef$getVmaAllocation() {
        return vmaAllocation;
    }

    @Inject(method = "destroy", at = @At("HEAD"), cancellable = true)
    private void mcef$destroyExternalVkImage(CallbackInfo ci) {
        if (!mcef$externalVkImage) {
            return;
        }

        ci.cancel();
        mcef$queueCurrentImageForDestroy();
    }

    @Unique
    private void mcef$queueCurrentImageForDestroy() {
        if (vkImage == 0L) {
            return;
        }

        var imageToDestroy = vkImage;
        var allocationToDestroy = vmaAllocation;
        if (mcef$externalVkImage) {
            if (mcef$externalVkImageDestroyed) {
                return;
            }

            mcef$externalVkImageDestroyed = true;
            var destroyAction = mcef$externalDestroyAction;
            device.createCommandEncoder().queueForDestroy(() -> {
                if (destroyAction != null) {
                    destroyAction.run();
                }
            });
            mcef$externalDestroyAction = null;
        } else {
            device.createCommandEncoder()
                    .queueForDestroy(() -> Vma.vmaDestroyImage(device.vma(), imageToDestroy, allocationToDestroy));
        }

        vkImage = 0L;
        vmaAllocation = 0L;
    }

}

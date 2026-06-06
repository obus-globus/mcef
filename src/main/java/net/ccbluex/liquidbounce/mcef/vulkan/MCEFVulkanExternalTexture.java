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

package net.ccbluex.liquidbounce.mcef.vulkan;

import org.jspecify.annotations.NullMarked;

@NullMarked
public interface MCEFVulkanExternalTexture {

    /**
     * Replaces the texture's internally allocated Vulkan image with an externally-created or imported image.
     * <p>
     * The image originally created by Minecraft is queued for destruction before the external handles are
     * installed. Queuing is intentional because the constructor may already have recorded commands that
     * reference the original image. After replacement, this texture no longer owns a VMA allocation through
     * Minecraft's normal {@code VulkanGpuTexture#destroy()} path. Instead, {@code destroyAction} is queued
     * exactly once when Blaze3D destroys the texture.
     * <p>
     * Callers are responsible for importing compatible memory, transitioning the image into the layout expected
     * by Blaze3D copy/render commands, and providing any external synchronization required by the producer.
     *
     * @param vkImage Vulkan image handle to expose through the texture. Must not be {@code VK_NULL_HANDLE}.
     * @param vmaAllocation VMA allocation handle for the external image, or {@code 0} when the image is not
     *                      backed by a VMA allocation owned by the destroy action.
     * @param destroyAction releases {@code vkImage}, {@code vmaAllocation}, and any platform handles associated
     *                      with the external import. It runs from Minecraft's Vulkan destruction queue, not
     *                      synchronously from this method.
     * @throws IllegalArgumentException if {@code vkImage} is {@code VK_NULL_HANDLE}.
     * @throws IllegalStateException if the texture is already closed.
     */
    void mcef$setExternalVkImage(long vkImage, long vmaAllocation, Runnable destroyAction);

    /**
     * Returns whether this texture has been switched to external Vulkan image ownership.
     * <p>
     * Once this returns {@code true}, Minecraft's VMA destroy path is bypassed for the texture even after
     * the external image has been released.
     */
    boolean mcef$isExternalVkImage();

    /**
     * Returns the currently installed Vulkan image handle, or {@code VK_NULL_HANDLE} after it has been destroyed.
     */
    long mcef$getVkImage();

    /**
     * Returns the currently installed VMA allocation handle, or {@code 0} when the image is not VMA-backed or has
     * already been destroyed.
     */
    long mcef$getVmaAllocation();

}

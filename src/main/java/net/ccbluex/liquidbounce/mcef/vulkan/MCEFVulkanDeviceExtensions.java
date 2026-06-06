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

import com.google.common.collect.Sets;
import org.jspecify.annotations.NullMarked;

import java.util.Set;

/**
 * Optional Vulkan device extensions used by MCEF accelerated paint importers.
 * <p>
 * These extensions must not be treated as Minecraft Vulkan backend requirements. They are enabled only when
 * the selected physical device already supports them, and accelerated paint importers use the enabled set as
 * a capability check before attempting platform texture import.
 */
@NullMarked
public final class MCEFVulkanDeviceExtensions {
    public static final Set<String> LINUX_DMABUF_IMPORT = Set.of(
            "VK_KHR_external_memory_fd",
            "VK_EXT_external_memory_dma_buf",
            "VK_EXT_image_drm_format_modifier"
    );

    public static final Set<String> WINDOWS_D3D11_IMPORT = Set.of(
            "VK_KHR_external_memory_win32"
    );

    public static final Set<String> OPTIONAL_EXTERNAL_MEMORY_IMPORT = Sets.union(LINUX_DMABUF_IMPORT, WINDOWS_D3D11_IMPORT);

    private MCEFVulkanDeviceExtensions() {
    }

}

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

package net.ccbluex.liquidbounce.mcef.cef;

import com.mojang.blaze3d.systems.GpuDevice;
import org.cef.handler.CefAcceleratedPaintInfo;
import org.jspecify.annotations.NullMarked;

/**
 * Context object for importing a CEF accelerated paint frame into the active Blaze3D backend.
 * <p>
 * The CEF payload describes the platform-owned frame for the duration of the accelerated paint callback.
 * The GPU device identifies the Minecraft renderer backend that must consume it. Importers should use this
 * object instead of growing method parameter lists as additional backend-specific state is needed.
 *
 * @param info CEF platform-specific accelerated paint payload.
 * @param device active Blaze3D GPU device on the render thread.
 * @param width frame width in pixels.
 * @param height frame height in pixels.
 */
@NullMarked
record AcceleratedPaintImportContext(
        CefAcceleratedPaintInfo info,
        GpuDevice device,
        int width,
        int height
) {

    boolean isOpenGlDevice() {
        return "OpenGL".equals(device.getDeviceInfo().backendName());
    }

    boolean isVulkanDevice() {
        return "Vulkan".equals(device.getDeviceInfo().backendName());
    }

}

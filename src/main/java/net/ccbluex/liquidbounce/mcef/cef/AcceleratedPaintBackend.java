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

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
sealed interface AcceleratedPaintBackend extends AutoCloseable
        permits LinuxAcceleratedPaintBackend, WindowsAcceleratedPaintBackend {

    /**
     * Returns whether this backend can import the frame described by {@code context}.
     * <p>
     * Implementations should check both the CEF platform payload and the active Blaze3D GPU backend. A platform
     * payload alone is not enough because the same CEF frame has to be imported differently for OpenGL and Vulkan.
     */
    boolean supports(AcceleratedPaintImportContext context);

    /**
     * Imports the platform frame as a temporary copy source.
     * <p>
     * The returned frame is only used long enough for the renderer to copy it into the stable display texture
     * owned by MCEF. Implementations must not expose CEF callback-owned resources as long-lived textures.
     */
    @Nullable AcceleratedPaintFrame importFrame(AcceleratedPaintImportContext context);

    @Override
    void close();

}

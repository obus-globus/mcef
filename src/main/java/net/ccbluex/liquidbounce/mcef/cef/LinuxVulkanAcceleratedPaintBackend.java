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

import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.vulkan.MCEFVulkanDeviceExtensions;
import org.cef.handler.CefAcceleratedPaintInfoLinux;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Vulkan accelerated paint importer for Linux dmabuf frames.
 * <p>
 * This backend currently owns Vulkan-specific capability checks and diagnostics. The actual dmabuf to
 * {@code VkImage} import is intentionally left for the next implementation phase, after Minecraft's Vulkan
 * device creation can opt into the required external-memory extensions.
 */
@NullMarked
final class LinuxVulkanAcceleratedPaintBackend implements AcceleratedPaintBackend {
    private boolean loggedMissingPlanes;
    private boolean loggedUnsupportedFormat;
    private boolean loggedMissingExtensions;
    private boolean loggedNotImplemented;

    @Override
    public boolean supports(AcceleratedPaintImportContext context) {
        return context.isVulkanDevice() && context.info() instanceof CefAcceleratedPaintInfoLinux;
    }

    @Override
    public @Nullable AcceleratedPaintFrame importFrame(AcceleratedPaintImportContext context) {
        var linuxInfo = (CefAcceleratedPaintInfoLinux) context.info();
        if (!linuxInfo.hasDmaBufPlanes()) {
            if (!loggedMissingPlanes) {
                MCEF.INSTANCE.LOGGER.warn("Linux Vulkan accelerated paint info has no dmabuf planes.");
                loggedMissingPlanes = true;
            }
            return null;
        }

        if (!isSupportedFormat(linuxInfo.format)) {
            if (!loggedUnsupportedFormat) {
                MCEF.INSTANCE.LOGGER.warn(
                        "Linux Vulkan accelerated paint format is unsupported: {}",
                        linuxInfo.format
                );
                loggedUnsupportedFormat = true;
            }
            return null;
        }

        var missingExtensions = missingRequiredExtensions(context);
        if (!missingExtensions.isEmpty()) {
            if (!loggedMissingExtensions) {
                MCEF.INSTANCE.LOGGER.warn(
                        "Linux Vulkan accelerated paint requires missing Vulkan device extensions: {}",
                        missingExtensions
                );
                loggedMissingExtensions = true;
            }
            return null;
        }

        if (!loggedNotImplemented) {
            MCEF.INSTANCE.LOGGER.warn("Linux Vulkan accelerated paint import is not implemented yet.");
            loggedNotImplemented = true;
        }
        return null;
    }

    @Override
    public void close() {
    }

    private boolean isSupportedFormat(int format) {
        return format == CefConstants.CEF_COLOR_TYPE_RGBA_8888
                || format == CefConstants.CEF_COLOR_TYPE_BGRA_8888;
    }

    private List<String> missingRequiredExtensions(AcceleratedPaintImportContext context) {
        var enabledExtensions = context.device().getDeviceInfo().underlyingExtensions();
        var missingExtensions = new ArrayList<String>();
        for (var extension : MCEFVulkanDeviceExtensions.LINUX_DMABUF_IMPORT) {
            if (!hasEnabledExtension(enabledExtensions, extension)) {
                missingExtensions.add(extension);
            }
        }
        return missingExtensions;
    }

    private boolean hasEnabledExtension(Set<String> enabledExtensions, String extension) {
        for (var enabledExtension : enabledExtensions) {
            if (enabledExtension.equals(extension) || enabledExtension.startsWith(extension + " ")) {
                return true;
            }
        }
        return false;
    }

}

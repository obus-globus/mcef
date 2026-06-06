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

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.vulkan.MCEFVulkanDeviceExtensions;
import net.ccbluex.liquidbounce.mcef.vulkan.MCEFVulkanExternalTexture;
import org.cef.handler.CefAcceleratedPaintInfoLinux;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.linux.FCNTL;
import org.lwjgl.system.linux.UNISTD;
import org.lwjgl.vulkan.EXTExternalMemoryDmaBuf;
import org.lwjgl.vulkan.EXTImageDrmFormatModifier;
import org.lwjgl.vulkan.EXTQueueFamilyForeign;
import org.lwjgl.vulkan.KHRExternalMemoryFd;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageDrmFormatModifierExplicitCreateInfoEXT;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImportMemoryFdInfoKHR;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryFdPropertiesKHR;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkSubresourceLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Vulkan accelerated paint importer for Linux dmabuf frames.
 * <p>
 * CEF owns the dmabuf file descriptors for the duration of the accelerated paint callback. Vulkan takes
 * ownership of any fd passed through {@code vkImportMemoryFdKHR} on successful import, so this backend first
 * duplicates the callback fd and imports only the duplicate. The imported image is then wrapped by a temporary
 * {@code VulkanGpuTexture} through {@link MCEFVulkanExternalTexture}; the texture is only used as a copy source
 * and is closed before the callback returns.
 */
@NullMarked
final class LinuxVulkanAcceleratedPaintBackend implements AcceleratedPaintBackend {
    private static final int TEXTURE_USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST;
    private static final int DMA_BUF_HANDLE_TYPE = EXTExternalMemoryDmaBuf.VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;

    private boolean loggedMissingPlanes;
    private boolean loggedUnsupportedFormat;
    private boolean loggedMissingExtensions;
    private boolean loggedUnsupportedPlanes;
    private boolean loggedImportFailure;

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

        var planeCount = usablePlaneCount(linuxInfo);
        if (planeCount <= 0) {
            logUnsupportedPlanes("Linux Vulkan accelerated paint has incomplete dmabuf plane metadata.");
            return null;
        }

        var sourceFd = sharedPlaneFd(linuxInfo, planeCount);
        if (sourceFd < 0) {
            logUnsupportedPlanes("Linux Vulkan accelerated paint with multiple plane fds is not supported yet.");
            return null;
        }

        var texture = context.device().createTexture(
                "MCEF Linux Vulkan Accelerated Paint Source",
                TEXTURE_USAGE,
                GpuFormat.RGBA8_UNORM,
                context.width(),
                context.height(),
                1,
                1
        );

        try {
            var externalTexture = (MCEFVulkanExternalTexture) texture;
            var vulkanDevice = externalTexture.mcef$getVulkanDevice();
            var importResult = importDmaBufImage(
                    linuxInfo,
                    planeCount,
                    sourceFd,
                    context.width(),
                    context.height(),
                    vulkanDevice
            );
            externalTexture.mcef$setExternalVkImage(importResult.vkImage, 0L, importResult::destroy);

            var bgra = linuxInfo.format == CefConstants.CEF_COLOR_TYPE_BGRA_8888;
            return new AcceleratedPaintFrame(texture, bgra, texture::close);
        } catch (RuntimeException exception) {
            texture.close();
            if (!loggedImportFailure) {
                MCEF.INSTANCE.LOGGER.warn("Linux Vulkan accelerated paint dmabuf import failed.", exception);
                loggedImportFailure = true;
            } else {
                MCEF.INSTANCE.LOGGER.debug("Linux Vulkan accelerated paint dmabuf import failed.", exception);
            }
            return null;
        }
    }

    @Override
    public void close() {
    }

    private DmaBufImportResult importDmaBufImage(
            CefAcceleratedPaintInfoLinux info,
            int planeCount,
            int sourceFd,
            int width,
            int height,
            VulkanDevice vulkanDevice
    ) {
        var vkDevice = vulkanDevice.vkDevice();
        var importedFd = -1;
        var fdOwnedByJava = false;
        var vkImage = 0L;
        var vkMemory = 0L;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            importedFd = duplicateFd(sourceFd);
            fdOwnedByJava = true;

            var externalMemory = VkExternalMemoryImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .handleTypes(DMA_BUF_HANDLE_TYPE);
            var planeLayouts = createPlaneLayouts(stack, info, planeCount);
            var drmModifier = VkImageDrmFormatModifierExplicitCreateInfoEXT.calloc(stack)
                    .sType$Default()
                    .drmFormatModifier(info.modifier)
                    .pPlaneLayouts(planeLayouts);
            externalMemory.pNext(drmModifier.address());

            var imageCreateInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(externalMemory)
                    .imageType(VK12.VK_IMAGE_TYPE_2D)
                    .extent(extent -> extent.set(width, height, 1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .format(vkFormat(info.format))
                    .tiling(EXTImageDrmFormatModifier.VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT)
                    .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                    .usage(VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                    .samples(VK12.VK_SAMPLE_COUNT_1_BIT);

            var imageHandle = stack.callocLong(1);
            checkVulkan(VK12.vkCreateImage(vkDevice, imageCreateInfo, null, imageHandle), "vkCreateImage");
            vkImage = imageHandle.get(0);

            var imageRequirements = VkMemoryRequirements.calloc(stack);
            VK12.vkGetImageMemoryRequirements(vkDevice, vkImage, imageRequirements);

            var fdProperties = VkMemoryFdPropertiesKHR.calloc(stack).sType$Default();
            checkVulkan(
                    KHRExternalMemoryFd.vkGetMemoryFdPropertiesKHR(vkDevice, DMA_BUF_HANDLE_TYPE, importedFd, fdProperties),
                    "vkGetMemoryFdPropertiesKHR"
            );

            var memoryTypeIndex = memoryTypeIndex(
                    vkDevice.getPhysicalDevice(),
                    imageRequirements.memoryTypeBits() & fdProperties.memoryTypeBits()
            );
            var dedicatedAllocateInfo = VkMemoryDedicatedAllocateInfo.calloc(stack)
                    .sType$Default()
                    .image(vkImage)
                    .buffer(0L);
            var importInfo = VkImportMemoryFdInfoKHR.calloc(stack)
                    .sType$Default()
                    .handleType(DMA_BUF_HANDLE_TYPE)
                    .fd(importedFd);
            dedicatedAllocateInfo.pNext(importInfo.address());
            var allocateInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(dedicatedAllocateInfo)
                    .allocationSize(imageRequirements.size())
                    .memoryTypeIndex(memoryTypeIndex);

            var memoryHandle = stack.callocLong(1);
            checkVulkan(VK12.vkAllocateMemory(vkDevice, allocateInfo, null, memoryHandle), "vkAllocateMemory");
            vkMemory = memoryHandle.get(0);
            fdOwnedByJava = false;
            importedFd = -1;

            checkVulkan(VK12.vkBindImageMemory(vkDevice, vkImage, vkMemory, 0L), "vkBindImageMemory");
            transitionToGeneralLayout(vulkanDevice, vkImage);

            var result = new DmaBufImportResult(vulkanDevice, vkImage, vkMemory);
            vkImage = 0L;
            vkMemory = 0L;
            return result;
        } finally {
            if (fdOwnedByJava && importedFd >= 0) {
                closeFd(importedFd);
            }

            if (vkMemory != 0L) {
                VK12.vkFreeMemory(vkDevice, vkMemory, null);
            }

            if (vkImage != 0L) {
                VK12.vkDestroyImage(vkDevice, vkImage, null);
            }
        }
    }

    private VkSubresourceLayout.Buffer createPlaneLayouts(
            MemoryStack stack,
            CefAcceleratedPaintInfoLinux info,
            int planeCount
    ) {
        var layouts = VkSubresourceLayout.calloc(planeCount, stack);
        for (int i = 0; i < planeCount; i++) {
            var stride = info.plane_strides[i];
            var offset = info.plane_offsets[i];
            if (stride <= 0 || offset < 0L) {
                throw new IllegalArgumentException("Invalid dmabuf plane layout at index " + i);
            }

            layouts.position(i)
                    .offset(offset)
                    .size(0L)
                    .rowPitch(stride)
                    .arrayPitch(0L)
                    .depthPitch(0L);
        }
        return layouts.position(0);
    }

    private void transitionToGeneralLayout(VulkanDevice vulkanDevice, long vkImage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var commandEncoder = vulkanDevice.createCommandEncoder();
            var commandBuffer = commandEncoder.allocateAndBeginTransientCommandBuffer();

            /*
             * The dmabuf producer owns the image through the foreign queue family. Acquiring it into
             * GENERAL preserves the already-written CEF contents; using UNDEFINED here would allow the
             * implementation to discard them before the copy.
             *
             * Current JCEF Linux accelerated paint metadata exposes dmabuf fds, plane layouts, and the DRM
             * modifier, but no sync fd or semaphore payload. Until that contract grows an explicit sync
             * primitive, this backend can only acquire ownership and rely on the callback-provided image being
             * ready for consumption when CEF invokes the paint callback.
             */
            var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
            barrier.oldLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            barrier.newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            barrier.srcAccessMask(0);
            barrier.dstAccessMask(VK12.VK_ACCESS_TRANSFER_READ_BIT | VK12.VK_ACCESS_MEMORY_READ_BIT);
            barrier.srcQueueFamilyIndex(EXTQueueFamilyForeign.VK_QUEUE_FAMILY_FOREIGN_EXT);
            barrier.dstQueueFamilyIndex(vulkanDevice.graphicsQueue().queueFamilyIndex());
            barrier.image(vkImage);
            barrier.subresourceRange()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            VK12.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null,
                    null,
                    barrier
            );
            checkVulkan(VK12.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
            commandEncoder.execute(commandBuffer);
        }
    }

    private int duplicateFd(int sourceFd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var errno = stack.callocInt(1);
            var duplicated = FCNTL.fcntli(errno, sourceFd, FCNTL.F_DUPFD_CLOEXEC, 0);
            if (duplicated < 0) {
                throw new IllegalStateException("F_DUPFD_CLOEXEC failed for dmabuf fd, errno=" + errno.get(0));
            }
            return duplicated;
        }
    }

    private void closeFd(int fd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            UNISTD.close(stack.callocInt(1), fd);
        }
    }

    private int memoryTypeIndex(org.lwjgl.vulkan.VkPhysicalDevice physicalDevice, int memoryTypeBits) {
        if (memoryTypeBits == 0) {
            throw new IllegalStateException("Imported dmabuf memory is not compatible with image memory requirements");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var memoryProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);
            for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
                if ((memoryTypeBits & (1 << i)) != 0) {
                    return i;
                }
            }
        }

        throw new IllegalStateException("No compatible Vulkan memory type for imported dmabuf");
    }

    private int usablePlaneCount(CefAcceleratedPaintInfoLinux info) {
        var planeCount = Math.min(info.plane_count, info.plane_fds.length);
        planeCount = Math.min(planeCount, info.plane_strides.length);
        planeCount = Math.min(planeCount, info.plane_offsets.length);
        return Math.min(planeCount, 4);
    }

    private int sharedPlaneFd(CefAcceleratedPaintInfoLinux info, int planeCount) {
        var fd = info.plane_fds[0];
        if (fd < 0) {
            return -1;
        }

        for (int i = 1; i < planeCount; i++) {
            if (info.plane_fds[i] != fd) {
                return -1;
            }
        }
        return fd;
    }

    private int vkFormat(int format) {
        return switch (format) {
            case CefConstants.CEF_COLOR_TYPE_RGBA_8888 -> VK12.VK_FORMAT_R8G8B8A8_UNORM;
            case CefConstants.CEF_COLOR_TYPE_BGRA_8888 -> VK12.VK_FORMAT_B8G8R8A8_UNORM;
            default -> throw new IllegalArgumentException("Unsupported CEF color type: " + format);
        };
    }

    private boolean isSupportedFormat(int format) {
        return format == CefConstants.CEF_COLOR_TYPE_RGBA_8888
                || format == CefConstants.CEF_COLOR_TYPE_BGRA_8888;
    }

    private void logUnsupportedPlanes(String message) {
        if (!loggedUnsupportedPlanes) {
            MCEF.INSTANCE.LOGGER.warn(message);
            loggedUnsupportedPlanes = true;
        } else {
            MCEF.INSTANCE.LOGGER.debug(message);
        }
    }

    private void checkVulkan(int result, String operation) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
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

    private record DmaBufImportResult(
            VulkanDevice vulkanDevice,
            long vkImage,
            long vkMemory
    ) {

        private void destroy() {
            var vkDevice = vulkanDevice.vkDevice();
            VK12.vkDestroyImage(vkDevice, vkImage, null);
            VK12.vkFreeMemory(vkDevice, vkMemory, null);
        }

    }

}

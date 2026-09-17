# Vulkanfish

GPU-driven terrain renderer for Minecraft (Fabric): near field as mesh-shaded meshlets with
Hi-Z occlusion culling, far field as GPU-meshed LOD quadtree, deferred lighting with soft
shadows, TAA, OIT water/glass, optional DLSS 4 (Super Resolution, Frame Generation,
Ray Reconstruction) on NVIDIA.

LOD defaults to 64 chunks for a smooth first start; 256+ chunks run effortlessly on good
hardware (setting in the Vulkanfish options, stored per install).

## Requirements

- Minecraft 26.2, Fabric Loader ≥ 0.19.5, Fabric API, Java 25
- GPU with Vulkan 1.2. Mesh shaders (`VK_EXT_mesh_shader`) are used when available;
  without them an automatic classic-raster fallback runs (same image, GPU culling kept).
- Ray-traced block light needs Ray Query support (flix off via `enableRaytracing: false`
  in `config/vulkanfish.json` or `-Dvulkanfish.rtOff`); DLSS needs NVIDIA + the native
  `vfngx` shim (built via `-PngxSdk=...`, otherwise DLSS stays off).

## Install

Build with `./gradlew build` (Slang shaders compile automatically) and put the jar from
`build/libs/` into `mods/`. Windows works for the full renderer; DLSS there needs MinGW
and the NGX SDK (best effort, never breaks the build).

## macOS

Strategy is MoltenVK (Vulkan-on-Metal, what Mojang uses), not a native Metal backend:
no mesh shaders there yet, so the classic-raster fallback + no-raytracing path cover it.
Slang can target Metal (`-target metal`), but host code, raytracing and upscaling
(MetalFX instead of NGX) would all need separate ports — deferred until MoltenVK
proves insufficient.

## Known issues (0.9.x beta)

- Edge/texture flicker under investigation (see issues). Bisection flags:
  `-Dvulkanfish.freezeTime=true` (wind/water/caustics frozen),
  `-Dvulkanfish.noShadow=true` (shadow map off). The 10 s log lines
  (`[Schatten neu/Cache]`, `[Phase2 raster/skip]`, `Terrain:`) show what still moves.
- Classic fallback and Windows build are compile-verified, real-hardware reports welcome.

## License

GNU General Public License v3 or later, see LICENSE.

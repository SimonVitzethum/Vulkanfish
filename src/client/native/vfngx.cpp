// vfngx.cpp – schlanker NVIDIA-NGX-Shim fuer Vulkanfish (DLSS 4: DLAA, Ray Reconstruction, Frame Generation).
// Aus Java per FFM aufgerufen (NgxBridge.java): flache C-Funktionen, Vulkan-Handles als 64 Bit.
// Nutzt die Inline-Helper des DLSS-SDK (NGX_VULKAN_CREATE/EVALUATE_DLSS_EXT), damit die Parameter
// genau wie in NVIDIAs Referenz gepackt werden; Init nach denselben Regeln wie RenderFX (ngx.cpp):
// beschreibbarer Datenpfad in BEIDEN Argumenten, UUID-Projekt-ID.
#include <vulkan/vulkan.h>
#include "nvsdk_ngx.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"
#include "nvsdk_ngx_helpers_dlssg_vk.h"
#include "nvsdk_ngx_params_dlssg.h"
#include "nvsdk_ngx_helpers_dlssd_vk.h"

#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <mutex>
#include <pthread.h>
#include <vector>

namespace {
// NGX braucht mehr Stack, als der Java-Hauptthread (Minecrafts Render-Thread, ~1 MB) hat: die
// Initialisierung stuerzte dort ab. Alle NGX-Aufrufe laufen deshalb auf einem eigenen Thread mit
// 64 MB Stack; der Aufrufer wartet (Command-Buffer bleiben so extern synchronisiert).
std::mutex gMx;
std::condition_variable gCv;
std::function<void()> gJob;
bool gJobDone = true, gWorker = false, gStop = false;
pthread_t gWorkerThread;

void* workerMain(void*) {
    for (;;) {
        std::function<void()> job;
        {
            std::unique_lock<std::mutex> l(gMx);
            gCv.wait(l, [] { return !gJobDone || gStop; });
            if (gStop) return nullptr;
            job = gJob;
        }
        job();
        {
            std::lock_guard<std::mutex> l(gMx);
            gJobDone = true;
        }
        gCv.notify_all();
    }
    return nullptr;
}

void onBigStack(const std::function<void()>& f) {
    std::unique_lock<std::mutex> l(gMx);
    if (!gWorker) {
        pthread_attr_t a;
        pthread_attr_init(&a);
        pthread_attr_setstacksize(&a, 64u << 20);
        if (pthread_create(&gWorkerThread, &a, workerMain, nullptr) != 0) {
            l.unlock();
            f(); // Notfall: im Aufrufer-Thread
            return;
        }
        gWorker = true;
    }
    gJob = f;
    gJobDone = false;
    gCv.notify_all();
    gCv.wait(l, [] { return gJobDone; });
}

VkDevice gDevice = VK_NULL_HANDLE;
bool gInit = false;
NVSDK_NGX_Parameter* gCaps = nullptr;
NVSDK_NGX_Parameter* gDlaaParams = nullptr;
NVSDK_NGX_Handle* gDlaa = nullptr;
uint32_t gDlaaW = 0, gDlaaH = 0, gDlaaOW = 0, gDlaaOH = 0;
char gLastError[256] = {0};
// Ray Reconstruction (DLSS 4, Transformer-Preset E) in DLAA-Aufloesung
NVSDK_NGX_Parameter* gRrParams = nullptr;
NVSDK_NGX_Handle* gRr = nullptr;
uint32_t gRrW = 0, gRrH = 0;
int gRrPreset = NVSDK_NGX_RayReconstruction_Hint_Render_Preset_E;
uint32_t gRrOW = 0, gRrOH = 0;
// DLSS Frame Generation (DLSS 4, bis 6x): Handle lazy beim ersten Aufruf, neu bei Groessen-/Formatwechsel
NVSDK_NGX_Parameter* gFgParams = nullptr;
NVSDK_NGX_Handle* gFg = nullptr;
uint32_t gFgW = 0, gFgH = 0, gFgRW = 0, gFgRH = 0;
int gFgFormat = 0;

void NVSDK_CONV ngxLog(const char* msg, NVSDK_NGX_Logging_Level, NVSDK_NGX_Feature) {
    std::fprintf(stderr, "[vulkanfish-ngx] %s", msg);
}

std::vector<uint32_t> wpath(const char* s) { // wchar_t ist unter Linux 4 Byte
    std::vector<uint32_t> w;
    for (; *s; ++s) w.push_back((uint32_t)(unsigned char)*s);
    w.push_back(0);
    return w;
}

NVSDK_NGX_Resource_VK wrap(uint64_t image, uint64_t view, int format, uint32_t w, uint32_t h, bool depth, bool rw) {
    VkImageSubresourceRange r{};
    r.aspectMask = depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
    r.levelCount = 1;
    r.layerCount = 1;
    return NVSDK_NGX_Create_ImageView_Resource_VK((VkImageView)view, (VkImage)image, r, (VkFormat)format, w, h, rw);
}

void err(const char* what, NVSDK_NGX_Result r) {
    std::snprintf(gLastError, sizeof gLastError, "%s: 0x%08X", what, (unsigned)r);
}
}  // namespace

extern "C" {

// Rueckgabe: Bitmaske verfuegbarer Features (1 = DLSS/DLAA, 2 = Frame Generation, 4 = Ray
// Reconstruction), -1 bei Init-Fehler (vfngx_error). multiFrameMax: groesste MFG-Anzahl.
static int initImpl(uint64_t instance, uint64_t physicalDevice, uint64_t device, uint64_t gipa, uint64_t gdpa,
               const char* dataPath, int verboseLog, int* multiFrameMax) {
    if (gInit) return -1;
    std::vector<uint32_t> wp = wpath(dataPath);
    const wchar_t* pathW = reinterpret_cast<const wchar_t*>(wp.data());
    const wchar_t* paths[1] = {pathW};
    NVSDK_NGX_FeatureCommonInfo fci;
    std::memset(&fci, 0, sizeof(fci));
    fci.PathListInfo.Path = paths;
    fci.PathListInfo.Length = 1;
    if (verboseLog) {
        fci.LoggingInfo.LoggingCallback = ngxLog;
        fci.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_VERBOSE;
    }
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_Init_with_ProjectID(
        "5d1f4c2e-7b0a-4a93-9c61-2e8f0b7d4a15", NVSDK_NGX_ENGINE_TYPE_CUSTOM, "1.0", pathW,
        (VkInstance)instance, (VkPhysicalDevice)physicalDevice, (VkDevice)device,
        (PFN_vkGetInstanceProcAddr)gipa, (PFN_vkGetDeviceProcAddr)gdpa, &fci, NVSDK_NGX_Version_API);
    if (NVSDK_NGX_FAILED(r)) {
        err("NVSDK_NGX_VULKAN_Init", r);
        return -1;
    }
    gInit = true;
    gDevice = (VkDevice)device;
    int mask = 0;
    if (NVSDK_NGX_SUCCEED(NVSDK_NGX_VULKAN_GetCapabilityParameters(&gCaps)) && gCaps) {
        int sr = 0, fg = 0, rr = 0;
        NVSDK_NGX_Parameter_GetI(gCaps, NVSDK_NGX_Parameter_SuperSampling_Available, &sr);
        NVSDK_NGX_Parameter_GetI(gCaps, "FrameGeneration.Available", &fg);
        NVSDK_NGX_Parameter_GetI(gCaps, "SuperSamplingDenoising.Available", &rr);
        unsigned int mfc = 0;
        if (fg) NVSDK_NGX_Parameter_GetUI(gCaps, "DLSSG.MultiFrameCountMax", &mfc);
        if (multiFrameMax) *multiFrameMax = (int)mfc;
        mask = (sr ? 1 : 0) | (fg ? 2 : 0) | (rr ? 4 : 0);
    }
    return mask;
}

const char* vfngx_error() {
    return gLastError;
}

// DLAA (DLSS 4, Transformer-Preset K) in den offenen Command-Buffer. Eingaben/Ausgabe im GENERAL-
// Layout; Farbe LDR, Tiefe Reverse-Z, Bewegungsvektoren in Pixeln (aktuell -> vorher), Jitter in
// Pixeln. Rueckgabe 0 = ok.
static NVSDK_NGX_PerfQuality_Value perfFor(uint32_t rw, uint32_t ow) {
    float s = ow ? (float)rw / (float)ow : 1.0f; // Render-/Ausgabeaufloesung
    return s >= 0.99f ? NVSDK_NGX_PerfQuality_Value_DLAA : s >= 0.62f ? NVSDK_NGX_PerfQuality_Value_MaxQuality
         : s >= 0.54f ? NVSDK_NGX_PerfQuality_Value_Balanced : s >= 0.42f ? NVSDK_NGX_PerfQuality_Value_MaxPerf
         : NVSDK_NGX_PerfQuality_Value_UltraPerformance;
}

// DLSS 4 (Super Resolution; bei gleicher Render- und Ausgabeaufloesung = DLAA)
static int dlaaImpl(uint64_t cmd, uint32_t w, uint32_t h, uint32_t ow, uint32_t oh,
               uint64_t colorImage, uint64_t colorView, int colorFormat,
               uint64_t depthImage, uint64_t depthView, int depthFormat,
               uint64_t mvImage, uint64_t mvView, int mvFormat,
               uint64_t outImage, uint64_t outView, int outFormat,
               float jitterX, float jitterY, int reset) {
    if (!gInit) return -1;
    VkCommandBuffer cb = (VkCommandBuffer)cmd;
    if (gDlaa && (gDlaaW != w || gDlaaH != h || gDlaaOW != ow || gDlaaOH != oh)) {
        NVSDK_NGX_VULKAN_ReleaseFeature(gDlaa); // Aufrufer wartet vorher auf die GPU (Groessenwechsel)
        gDlaa = nullptr;
    }
    if (!gDlaa) {
        if (!gDlaaParams && NVSDK_NGX_FAILED(NVSDK_NGX_VULKAN_AllocateParameters(&gDlaaParams))) return -2;
        NVSDK_NGX_DLSS_Create_Params cp;
        std::memset(&cp, 0, sizeof(cp));
        cp.Feature.InWidth = w;
        cp.Feature.InHeight = h;
        cp.Feature.InTargetWidth = ow;
        cp.Feature.InTargetHeight = oh;
        cp.Feature.InPerfQualityValue = perfFor(w, ow);
        // Bewegungsvektoren in Renderaufloesung (= Ausgabe bei DLAA), ohne Jitter; Reverse-Z; LDR
        cp.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_MVLowRes | NVSDK_NGX_DLSS_Feature_Flags_DepthInverted;
        // DLSS 4: Transformer-Modell (Preset K) ausdruecklich, statt "Default"
        for (const char* k : {NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality,
                              NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance,
                              NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance})
            NVSDK_NGX_Parameter_SetUI(gDlaaParams, k, NVSDK_NGX_DLSS_Hint_Render_Preset_K);
        NVSDK_NGX_Result r = NGX_VULKAN_CREATE_DLSS_EXT(cb, 1, 1, &gDlaa, gDlaaParams, &cp);
        if (NVSDK_NGX_FAILED(r)) {
            err("CreateFeature DLAA", r);
            gDlaa = nullptr;
            return -3;
        }
        gDlaaW = w;
        gDlaaH = h;
        gDlaaOW = ow;
        gDlaaOH = oh;
        reset = 1;
    }
    NVSDK_NGX_Resource_VK color = wrap(colorImage, colorView, colorFormat, w, h, false, false);
    NVSDK_NGX_Resource_VK depth = wrap(depthImage, depthView, depthFormat, w, h, true, false);
    NVSDK_NGX_Resource_VK motion = wrap(mvImage, mvView, mvFormat, w, h, false, false);
    NVSDK_NGX_Resource_VK out = wrap(outImage, outView, outFormat, ow, oh, false, true);
    NVSDK_NGX_VK_DLSS_Eval_Params ep;
    std::memset(&ep, 0, sizeof(ep));
    ep.Feature.pInColor = &color;
    ep.Feature.pInOutput = &out;
    ep.pInDepth = &depth;
    ep.pInMotionVectors = &motion;
    ep.InJitterOffsetX = jitterX;
    ep.InJitterOffsetY = jitterY;
    ep.InRenderSubrectDimensions.Width = w;
    ep.InRenderSubrectDimensions.Height = h;
    ep.InReset = reset ? 1 : 0;
    ep.InMVScaleX = 1.0f;
    ep.InMVScaleY = 1.0f;
    NVSDK_NGX_Result r = NGX_VULKAN_EVALUATE_DLSS_EXT(cb, gDlaa, gDlaaParams, &ep);
    if (NVSDK_NGX_FAILED(r)) {
        err("EvaluateFeature DLAA", r);
        return -4;
    }
    return 0;
}

// Ray Reconstruction: entrauscht + glaettet in einem Schritt. img/view/fmt je Ressource in der
// Reihenfolge Farbe, Tiefe, Bewegung, diffuse Albedo, spekulare Albedo, Normale(+Rauheit in w), Ausgabe.
// m: 16 worldToView, 16 viewToClip (wie bei der FG: JOML-Spalten = NGX-Zeilen).
static int rrImpl(uint64_t cmd, uint32_t w, uint32_t h, uint32_t ow, uint32_t oh, const uint64_t* img, const uint64_t* view, const int* fmt,
                  const float* m, float jitterX, float jitterY, int reset) {
    if (!gInit) return -1;
    VkCommandBuffer cb = (VkCommandBuffer)cmd;
    if (gRr && (gRrW != w || gRrH != h || gRrOW != ow || gRrOH != oh)) {
        NVSDK_NGX_VULKAN_ReleaseFeature(gRr);
        gRr = nullptr;
    }
    if (!gRr) {
        if (!gRrParams && NVSDK_NGX_FAILED(NVSDK_NGX_VULKAN_AllocateParameters(&gRrParams))) return -2;
        NVSDK_NGX_DLSSD_Create_Params cp;
        std::memset(&cp, 0, sizeof(cp));
        cp.InWidth = w;
        cp.InHeight = h;
        cp.InTargetWidth = ow;
        cp.InTargetHeight = oh;
        cp.InPerfQualityValue = perfFor(w, ow);
        cp.InDenoiseMode = NVSDK_NGX_DLSS_Denoise_Mode_DLUnified;
        cp.InRoughnessMode = NVSDK_NGX_DLSS_Roughness_Mode_Packed; // Rauheit in normals.w
        cp.InUseHWDepth = NVSDK_NGX_DLSS_Depth_Type_HW;
        // RR verlangt HDR-Farbe (der Aufrufer spreizt sein LDR-Bild umkehrbar auf)
        cp.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_MVLowRes | NVSDK_NGX_DLSS_Feature_Flags_DepthInverted
                                | NVSDK_NGX_DLSS_Feature_Flags_IsHDR;
        for (const char* k : {NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_DLAA,
                              NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Quality,
                              NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Balanced,
                              NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Performance,
                              NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_UltraPerformance})
            NVSDK_NGX_Parameter_SetUI(gRrParams, k, (unsigned)gRrPreset);
        NVSDK_NGX_Result r = NGX_VULKAN_CREATE_DLSSD_EXT1(gDevice, cb, 1, 1, &gRr, gRrParams, &cp);
        if (NVSDK_NGX_FAILED(r)) {
            err("CreateFeature Ray Reconstruction", r);
            gRr = nullptr;
            return -3;
        }
        gRrW = w;
        gRrH = h;
        gRrOW = ow;
        gRrOH = oh;
        reset = 1;
    }
    NVSDK_NGX_Resource_VK color = wrap(img[0], view[0], fmt[0], w, h, false, false);
    NVSDK_NGX_Resource_VK depth = wrap(img[1], view[1], fmt[1], w, h, true, false);
    NVSDK_NGX_Resource_VK motion = wrap(img[2], view[2], fmt[2], w, h, false, false);
    NVSDK_NGX_Resource_VK diffuse = wrap(img[3], view[3], fmt[3], w, h, false, false);
    NVSDK_NGX_Resource_VK specular = wrap(img[4], view[4], fmt[4], w, h, false, false);
    NVSDK_NGX_Resource_VK normals = wrap(img[5], view[5], fmt[5], w, h, false, false);
    NVSDK_NGX_Resource_VK out = wrap(img[6], view[6], fmt[6], ow, oh, false, true);
    float w2v[16], v2c[16];
    std::memcpy(w2v, m, sizeof(w2v));
    std::memcpy(v2c, m + 16, sizeof(v2c));
    NVSDK_NGX_VK_DLSSD_Eval_Params ep;
    std::memset(&ep, 0, sizeof(ep));
    ep.pInColor = &color;
    ep.pInOutput = &out;
    ep.pInDepth = &depth;
    ep.pInMotionVectors = &motion;
    ep.pInDiffuseAlbedo = &diffuse;
    ep.pInSpecularAlbedo = &specular;
    ep.pInNormals = &normals;
    ep.pInWorldToViewMatrix = w2v;
    ep.pInViewToClipMatrix = v2c;
    ep.InJitterOffsetX = jitterX;
    ep.InJitterOffsetY = jitterY;
    ep.InRenderSubrectDimensions.Width = w;
    ep.InRenderSubrectDimensions.Height = h;
    ep.InReset = reset ? 1 : 0;
    ep.InMVScaleX = 1.0f;
    ep.InMVScaleY = 1.0f;
    NVSDK_NGX_Result r = NGX_VULKAN_EVALUATE_DLSSD_EXT(cb, gRr, gRrParams, &ep);
    if (NVSDK_NGX_FAILED(r)) {
        err("EvaluateFeature Ray Reconstruction", r);
        return -4;
    }
    return 0;
}

// Ein generiertes Bild (Index frameIndex von frameCount, 1-basiert) in den offenen Command-Buffer.
// p: 16 viewToClip, 16 clipToPrevClip (beide zeilenweise fuer Zeilenvektoren, ohne Jitter),
// 2 Jitter (Pixel), 3 Kamera-Position, 3 rechts, 3 oben, 3 vorn, near, far, FOV (rad), Seitenverhaeltnis.
static int fgImpl(uint64_t cmd, uint32_t w, uint32_t h, uint32_t rw, uint32_t rh,
                  uint64_t bbImage, uint64_t bbView, int bbFormat,
                  uint64_t depthImage, uint64_t depthView, int depthFormat,
                  uint64_t mvImage, uint64_t mvView, int mvFormat,
                  uint64_t hudImage, uint64_t hudView, int hudFormat,
                  uint64_t outImage, uint64_t outView, int outFormat,
                  const float* p, int frameIndex, int frameCount, int reset) {
    if (!gInit) return -1;
    VkCommandBuffer cb = (VkCommandBuffer)cmd;
    if (gFg && (gFgW != w || gFgH != h || gFgRW != rw || gFgRH != rh || gFgFormat != bbFormat)) {
        NVSDK_NGX_VULKAN_ReleaseFeature(gFg); // Aufrufer wartet vorher auf die GPU
        gFg = nullptr;
    }
    if (!gFg) {
        if (!gFgParams && NVSDK_NGX_FAILED(NVSDK_NGX_VULKAN_AllocateParameters(&gFgParams))) return -2;
        NVSDK_NGX_DLSSG_Create_Params cp;
        std::memset(&cp, 0, sizeof(cp));
        cp.Width = w;
        cp.Height = h;
        cp.NativeBackbufferFormat = (unsigned int)bbFormat; // Vulkan: VkFormat-Wert
        cp.RenderWidth = rw;   // Tiefe + Bewegungsvektoren in Renderaufloesung (DLSS SR davor)
        cp.RenderHeight = rh;
        cp.DynamicResolutionScaling = false;
        NVSDK_NGX_Result r = NGX_VK_CREATE_DLSSG(cb, 1, 1, &gFg, gFgParams, &cp);
        if (NVSDK_NGX_FAILED(r)) {
            err("CreateFeature DLSS-G", r);
            gFg = nullptr;
            return -3;
        }
        gFgW = w;
        gFgH = h;
        gFgRW = rw;
        gFgRH = rh;
        gFgFormat = bbFormat;
        reset = 1;
    }
    NVSDK_NGX_Resource_VK backbuffer = wrap(bbImage, bbView, bbFormat, w, h, false, false);
    NVSDK_NGX_Resource_VK depth = wrap(depthImage, depthView, depthFormat, rw, rh, true, false);
    NVSDK_NGX_Resource_VK mvecs = wrap(mvImage, mvView, mvFormat, rw, rh, false, false);
    NVSDK_NGX_Resource_VK hudless = wrap(hudImage, hudView, hudFormat, w, h, false, false);
    NVSDK_NGX_Resource_VK out = wrap(outImage, outView, outFormat, w, h, false, true);
    NVSDK_NGX_VK_DLSSG_Eval_Params ep;
    std::memset(&ep, 0, sizeof(ep));
    ep.pBackbuffer = &backbuffer;
    ep.pMVecs = &mvecs;
    ep.pDepth = &depth;
    if (hudImage) ep.pHudless = &hudless; // Szene ohne GUI: NGX haelt die GUI ruhig
    ep.pOutputInterpFrame = &out;
    NVSDK_NGX_DLSSG_Opt_Eval_Params opt{}; // Wert-Init behaelt die Header-Vorgaben
    opt.multiFrameCount = (unsigned)frameCount;
    opt.multiFrameIndex = (unsigned)frameIndex;
    float v2c[16], c2v[16], c2p[16], p2c[16], lens[16];
    std::memcpy(v2c, p, sizeof(v2c));
    std::memcpy(c2p, p + 16, sizeof(c2p));
    auto inv = [](const float* m, float* o) { // 4x4-Inverse (Kofaktoren)
        float t[16];
        t[0] = m[5]*m[10]*m[15] - m[5]*m[11]*m[14] - m[9]*m[6]*m[15] + m[9]*m[7]*m[14] + m[13]*m[6]*m[11] - m[13]*m[7]*m[10];
        t[4] = -m[4]*m[10]*m[15] + m[4]*m[11]*m[14] + m[8]*m[6]*m[15] - m[8]*m[7]*m[14] - m[12]*m[6]*m[11] + m[12]*m[7]*m[10];
        t[8] = m[4]*m[9]*m[15] - m[4]*m[11]*m[13] - m[8]*m[5]*m[15] + m[8]*m[7]*m[13] + m[12]*m[5]*m[11] - m[12]*m[7]*m[9];
        t[12] = -m[4]*m[9]*m[14] + m[4]*m[10]*m[13] + m[8]*m[5]*m[14] - m[8]*m[6]*m[13] - m[12]*m[5]*m[10] + m[12]*m[6]*m[9];
        t[1] = -m[1]*m[10]*m[15] + m[1]*m[11]*m[14] + m[9]*m[2]*m[15] - m[9]*m[3]*m[14] - m[13]*m[2]*m[11] + m[13]*m[3]*m[10];
        t[5] = m[0]*m[10]*m[15] - m[0]*m[11]*m[14] - m[8]*m[2]*m[15] + m[8]*m[3]*m[14] + m[12]*m[2]*m[11] - m[12]*m[3]*m[10];
        t[9] = -m[0]*m[9]*m[15] + m[0]*m[11]*m[13] + m[8]*m[1]*m[15] - m[8]*m[3]*m[13] - m[12]*m[1]*m[11] + m[12]*m[3]*m[9];
        t[13] = m[0]*m[9]*m[14] - m[0]*m[10]*m[13] - m[8]*m[1]*m[14] + m[8]*m[2]*m[13] + m[12]*m[1]*m[10] - m[12]*m[2]*m[9];
        t[2] = m[1]*m[6]*m[15] - m[1]*m[7]*m[14] - m[5]*m[2]*m[15] + m[5]*m[3]*m[14] + m[13]*m[2]*m[7] - m[13]*m[3]*m[6];
        t[6] = -m[0]*m[6]*m[15] + m[0]*m[7]*m[14] + m[4]*m[2]*m[15] - m[4]*m[3]*m[14] - m[12]*m[2]*m[7] + m[12]*m[3]*m[6];
        t[10] = m[0]*m[5]*m[15] - m[0]*m[7]*m[13] - m[4]*m[1]*m[15] + m[4]*m[3]*m[13] + m[12]*m[1]*m[7] - m[12]*m[3]*m[5];
        t[14] = -m[0]*m[5]*m[14] + m[0]*m[6]*m[13] + m[4]*m[1]*m[14] - m[4]*m[2]*m[13] - m[12]*m[1]*m[6] + m[12]*m[2]*m[5];
        t[3] = -m[1]*m[6]*m[11] + m[1]*m[7]*m[10] + m[5]*m[2]*m[11] - m[5]*m[3]*m[10] - m[9]*m[2]*m[7] + m[9]*m[3]*m[6];
        t[7] = m[0]*m[6]*m[11] - m[0]*m[7]*m[10] - m[4]*m[2]*m[11] + m[4]*m[3]*m[10] + m[8]*m[2]*m[7] - m[8]*m[3]*m[6];
        t[11] = -m[0]*m[5]*m[11] + m[0]*m[7]*m[9] + m[4]*m[1]*m[11] - m[4]*m[3]*m[9] - m[8]*m[1]*m[7] + m[8]*m[3]*m[5];
        t[15] = m[0]*m[5]*m[10] - m[0]*m[6]*m[9] - m[4]*m[1]*m[10] + m[4]*m[2]*m[9] + m[8]*m[1]*m[6] - m[8]*m[2]*m[5];
        float det = m[0]*t[0] + m[1]*t[4] + m[2]*t[8] + m[3]*t[12];
        for (int i = 0; i < 16; ++i) o[i] = det != 0.0f ? t[i] / det : (i % 5 == 0 ? 1.0f : 0.0f);
    };
    inv(v2c, c2v);
    inv(c2p, p2c);
    for (int i = 0; i < 16; ++i) lens[i] = (i % 5 == 0) ? 1.0f : 0.0f;
    std::memcpy(opt.cameraViewToClip, v2c, sizeof(v2c));
    std::memcpy(opt.clipToCameraView, c2v, sizeof(c2v));
    std::memcpy(opt.clipToLensClip, lens, sizeof(lens));
    std::memcpy(opt.clipToPrevClip, c2p, sizeof(c2p));
    std::memcpy(opt.prevClipToClip, p2c, sizeof(p2c));
    opt.jitterOffset[0] = p[32];
    opt.jitterOffset[1] = p[33];
    opt.mvecScale[0] = 1.0f; // Bewegungsvektoren in Pixeln (aktuell -> vorher), wie beim DLAA
    opt.mvecScale[1] = 1.0f;
    for (int i = 0; i < 3; ++i) {
        opt.cameraPos[i] = p[34 + i];
        opt.cameraRight[i] = p[37 + i];
        opt.cameraUp[i] = p[40 + i];
        opt.cameraFwd[i] = p[43 + i];
    }
    opt.cameraNear = p[46];
    opt.cameraFar = p[47];
    opt.cameraFOV = p[48];
    opt.cameraAspectRatio = p[49];
    opt.cameraMotionIncluded = true;
    opt.depthInverted = true; // Reverse-Z
    opt.reset = reset ? true : false;
    NVSDK_NGX_Result r = NGX_VK_EVALUATE_DLSSG(cb, gFg, gFgParams, &ep, &opt);
    if (NVSDK_NGX_FAILED(r)) {
        err("EvaluateFeature DLSS-G", r);
        return -4;
    }
    return 0;
}

static void shutdownImpl() {
    if (!gInit) return;
    if (gFg) NVSDK_NGX_VULKAN_ReleaseFeature(gFg);
    if (gFgParams) NVSDK_NGX_VULKAN_DestroyParameters(gFgParams);
    gFg = nullptr;
    gFgParams = nullptr;
    if (gRr) NVSDK_NGX_VULKAN_ReleaseFeature(gRr);
    if (gRrParams) NVSDK_NGX_VULKAN_DestroyParameters(gRrParams);
    gRr = nullptr;
    gRrParams = nullptr;
    if (gDlaa) NVSDK_NGX_VULKAN_ReleaseFeature(gDlaa);
    if (gDlaaParams) NVSDK_NGX_VULKAN_DestroyParameters(gDlaaParams);
    NVSDK_NGX_VULKAN_Shutdown1(gDevice);
    gDlaa = nullptr;
    gDlaaParams = nullptr;
    gInit = false;
}

int vfngx_init(uint64_t instance, uint64_t physicalDevice, uint64_t device, uint64_t gipa, uint64_t gdpa,
               const char* dataPath, int verboseLog, int* multiFrameMax) {
    int r = -1;
    onBigStack([&] { r = initImpl(instance, physicalDevice, device, gipa, gdpa, dataPath, verboseLog, multiFrameMax); });
    return r;
}

int vfngx_dlaa(uint64_t cmd, uint32_t w, uint32_t h, uint32_t ow, uint32_t oh,
               uint64_t colorImage, uint64_t colorView, int colorFormat,
               uint64_t depthImage, uint64_t depthView, int depthFormat,
               uint64_t mvImage, uint64_t mvView, int mvFormat,
               uint64_t outImage, uint64_t outView, int outFormat,
               float jitterX, float jitterY, int reset) {
    int r = -1;
    onBigStack([&] {
        r = dlaaImpl(cmd, w, h, ow, oh, colorImage, colorView, colorFormat, depthImage, depthView, depthFormat,
                     mvImage, mvView, mvFormat, outImage, outView, outFormat, jitterX, jitterY, reset);
    });
    return r;
}

// Optionen: 1 = Ray-Reconstruction-Preset (4 = D, 5 = E). Wirkt beim naechsten Anlegen des Features.
void vfngx_option(int key, int value) {
    if (key == 1) gRrPreset = value;
}

int vfngx_rr(uint64_t cmd, uint32_t w, uint32_t h, uint32_t ow, uint32_t oh, const uint64_t* img, const uint64_t* view, const int* fmt,
             const float* m, float jitterX, float jitterY, int reset) {
    int r = -1;
    onBigStack([&] { r = rrImpl(cmd, w, h, ow, oh, img, view, fmt, m, jitterX, jitterY, reset); });
    return r;
}

int vfngx_fg(uint64_t cmd, uint32_t w, uint32_t h, uint32_t rw, uint32_t rh,
             uint64_t bbImage, uint64_t bbView, int bbFormat,
             uint64_t depthImage, uint64_t depthView, int depthFormat,
             uint64_t mvImage, uint64_t mvView, int mvFormat,
             uint64_t hudImage, uint64_t hudView, int hudFormat,
             uint64_t outImage, uint64_t outView, int outFormat,
             const float* params, int frameIndex, int frameCount, int reset) {
    int r = -1;
    onBigStack([&] {
        r = fgImpl(cmd, w, h, rw, rh, bbImage, bbView, bbFormat, depthImage, depthView, depthFormat, mvImage, mvView, mvFormat,
                   hudImage, hudView, hudFormat, outImage, outView, outFormat, params, frameIndex, frameCount, reset);
    });
    return r;
}

void vfngx_shutdown() {
    onBigStack([] { shutdownImpl(); });
    // Worker beenden: sonst wartet er beim Prozessende auf eine schon abgebaute Condition-Variable
    bool had;
    {
        std::lock_guard<std::mutex> l(gMx);
        had = gWorker;
        gStop = true;
        gWorker = false;
    }
    gCv.notify_all();
    if (had) pthread_join(gWorkerThread, nullptr);
    gStop = false;
}

}  // extern "C"

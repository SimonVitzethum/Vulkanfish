// vfngx.cpp – schlanker NVIDIA-NGX-Shim fuer Vulkanfish (DLSS 4: DLAA; Frame Generation folgt).
// Aus Java per FFM aufgerufen (NgxBridge.java): flache C-Funktionen, Vulkan-Handles als 64 Bit.
// Nutzt die Inline-Helper des DLSS-SDK (NGX_VULKAN_CREATE/EVALUATE_DLSS_EXT), damit die Parameter
// genau wie in NVIDIAs Referenz gepackt werden; Init nach denselben Regeln wie RenderFX (ngx.cpp):
// beschreibbarer Datenpfad in BEIDEN Argumenten, UUID-Projekt-ID.
#include <vulkan/vulkan.h>
#include "nvsdk_ngx.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"

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
uint32_t gDlaaW = 0, gDlaaH = 0;
char gLastError[256] = {0};

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
static int dlaaImpl(uint64_t cmd, uint32_t w, uint32_t h,
               uint64_t colorImage, uint64_t colorView, int colorFormat,
               uint64_t depthImage, uint64_t depthView, int depthFormat,
               uint64_t mvImage, uint64_t mvView, int mvFormat,
               uint64_t outImage, uint64_t outView, int outFormat,
               float jitterX, float jitterY, int reset) {
    if (!gInit) return -1;
    VkCommandBuffer cb = (VkCommandBuffer)cmd;
    if (gDlaa && (gDlaaW != w || gDlaaH != h)) {
        NVSDK_NGX_VULKAN_ReleaseFeature(gDlaa); // Aufrufer wartet vorher auf die GPU (Groessenwechsel)
        gDlaa = nullptr;
    }
    if (!gDlaa) {
        if (!gDlaaParams && NVSDK_NGX_FAILED(NVSDK_NGX_VULKAN_AllocateParameters(&gDlaaParams))) return -2;
        NVSDK_NGX_DLSS_Create_Params cp;
        std::memset(&cp, 0, sizeof(cp));
        cp.Feature.InWidth = w;
        cp.Feature.InHeight = h;
        cp.Feature.InTargetWidth = w;
        cp.Feature.InTargetHeight = h;
        cp.Feature.InPerfQualityValue = NVSDK_NGX_PerfQuality_Value_DLAA;
        // Bewegungsvektoren in Renderaufloesung (= Ausgabe bei DLAA), ohne Jitter; Reverse-Z; LDR
        cp.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_MVLowRes | NVSDK_NGX_DLSS_Feature_Flags_DepthInverted;
        // DLSS 4: Transformer-Modell (Preset K) ausdruecklich, statt "Default"
        NVSDK_NGX_Parameter_SetUI(gDlaaParams, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA, NVSDK_NGX_DLSS_Hint_Render_Preset_K);
        NVSDK_NGX_Result r = NGX_VULKAN_CREATE_DLSS_EXT(cb, 1, 1, &gDlaa, gDlaaParams, &cp);
        if (NVSDK_NGX_FAILED(r)) {
            err("CreateFeature DLAA", r);
            gDlaa = nullptr;
            return -3;
        }
        gDlaaW = w;
        gDlaaH = h;
        reset = 1;
    }
    NVSDK_NGX_Resource_VK color = wrap(colorImage, colorView, colorFormat, w, h, false, false);
    NVSDK_NGX_Resource_VK depth = wrap(depthImage, depthView, depthFormat, w, h, true, false);
    NVSDK_NGX_Resource_VK motion = wrap(mvImage, mvView, mvFormat, w, h, false, false);
    NVSDK_NGX_Resource_VK out = wrap(outImage, outView, outFormat, w, h, false, true);
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

static void shutdownImpl() {
    if (!gInit) return;
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

int vfngx_dlaa(uint64_t cmd, uint32_t w, uint32_t h,
               uint64_t colorImage, uint64_t colorView, int colorFormat,
               uint64_t depthImage, uint64_t depthView, int depthFormat,
               uint64_t mvImage, uint64_t mvView, int mvFormat,
               uint64_t outImage, uint64_t outView, int outFormat,
               float jitterX, float jitterY, int reset) {
    int r = -1;
    onBigStack([&] {
        r = dlaaImpl(cmd, w, h, colorImage, colorView, colorFormat, depthImage, depthView, depthFormat,
                     mvImage, mvView, mvFormat, outImage, outView, outFormat, jitterX, jitterY, reset);
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

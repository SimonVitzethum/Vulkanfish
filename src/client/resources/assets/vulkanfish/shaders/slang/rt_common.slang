// rt_common.slang – geteilte Definitionen fuer das Raytracing-Blocklicht (Lichtliste + Gitter).
#ifndef VULKANFISH_RT_COMMON_SLANG
#define VULKANFISH_RT_COMMON_SLANG

// Vom Mesher: Welt-Position des Leuchtpunkts, gepackt: Emission 4 Bit | Radius 4 Bit (x/30 Bloecke) | RGB8
struct RtLight
{
    float3 pos;
    uint packed;
};

static const int LIGHT_GRID = 16;   // Zellen pro Achse (RtAccel.GRID_CELLS)
static const int LIGHT_CELL = 8;    // Bloecke pro Zelle
static const uint LIGHT_CAP = 24;   // Lichter pro Zelle (RtAccel.CELL_CAP)

float rtLightEmission(uint p) { return float(p & 0xF); }
float rtLightRadius(uint p) { return float((p >> 4) & 0xF) / 30.0; }
float3 rtLightColor(uint p)
{
    return float3(float((p >> 8) & 0xFF), float((p >> 16) & 0xFF), float(p >> 24)) / 255.0;
}

// Unverschattete Staerke am Punkt: faellt wie Vanillas Stufen linear bis zur Reichweite ab
float rtLightFalloff(float dist, float emission)
{
    float t = saturate(1.0 - dist / (emission + 0.5));
    return t * t * (emission / 15.0);
}

#endif

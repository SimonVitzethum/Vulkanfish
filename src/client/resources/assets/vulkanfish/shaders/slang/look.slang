// look.slang – Vulkanfish-Look (eigene Implementierung): warmes Sonnenlicht,
// kuehles Himmels-Ambient, orange Fackeln, weiche verzerrte Schatten,
// Verlaufs-Himmel mit Sonnen-Halo, Border-Fog in den Himmel, filmische Kurve.
// Alles linear/HDR; erst tonemap() geht zurueck nach Gamma.
#ifndef VULKANFISH_LOOK_SLANG
#define VULKANFISH_LOOK_SLANG

#include "gpu_scene.slang"

static const float PI = 3.14159265;

// ---------- Farbraum ----------
float3 toLinear(float3 c) { return pow(max(c, 0.0), float3(2.2)); }
float3 toGamma(float3 c) { return pow(max(c, 0.0), float3(1.0 / 2.2)); }
float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

// ---------- Normalen (Oktaeder, 2x 8 Bit im G-Buffer) ----------
float2 octWrap(float2 v) { return (1.0 - abs(v.yx)) * float2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0); }
float2 octEncode(float3 n)
{
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    float2 e = n.z >= 0.0 ? n.xy : octWrap(n.xy);
    return e * 0.5 + 0.5;
}
float3 octDecode(float2 e)
{
    e = e * 2.0 - 1.0;
    float3 n = float3(e.x, e.y, 1.0 - abs(e.x) - abs(e.y));
    float t = saturate(-n.z);
    n.xy += float2(n.x >= 0.0 ? -t : t, n.y >= 0.0 ? -t : t);
    return normalize(n);
}

// ---------- Materialien (4 Bit im Vertex-Alpha, 4 Bit Emission) ----------
static const uint MAT_DEFAULT = 0;
static const uint MAT_LEAVES = 1;
static const uint MAT_PLANT = 2;      // untere Vertices (fest)
static const uint MAT_PLANT_TOP = 3;  // obere Vertices (wehen)
static const uint MAT_VINE = 4;
static const uint MAT_ORE = 5;
static const uint MAT_LAVA = 6;
static const uint MAT_WATER = 7;
static const uint MAT_GLASS = 8;   // transluzente Bloecke (Glas, getoentes Glas, Slime, Honig)
static const uint MAT_ICE = 9;

bool isFoliage(uint m) { return m >= MAT_LEAVES && m <= MAT_VINE; }

// Wind: zwei ueberlagerte Wellen, raeumlich versetzt, sanft
float3 windOffset(float3 wp, float t, float amount, float rain)
{
    float strength = amount * (1.0 + rain * 1.5);
    float ph = wp.x * 0.37 + wp.z * 0.29 + wp.y * 0.13;
    float w1 = sin(t * 1.9 + ph);
    float w2 = sin(t * 3.7 + ph * 1.7) * 0.35;
    return float3(w1 + w2, 0.0, (w1 - w2) * 0.6) * strength;
}

// ---------- Licht ----------
// Sonnenfarbe: Mittag fast weiss-warm, tief am Horizont kraeftig orange.
float3 sunColor(FrameUniforms f)
{
    float3 noon = float3(1.00, 0.93, 0.82) * 2.3;
    float3 low = float3(1.00, 0.50, 0.20) * 1.7;
    float3 c = lerp(low, noon, smoothstep(0.0, 0.55, f.noonFactor));
    return c * lerp(1.0, 0.12, f.rainFactor);
}

float3 moonColor(FrameUniforms f)
{
    return float3(0.36, 0.48, 0.78) * (0.10 + 0.18 * f.moonBrightness) * lerp(1.0, 0.25, f.rainFactor);
}

// Licht, das die Schatten wirft (Sonne am Tag, Mond in der Nacht)
float3 directColor(FrameUniforms f)
{
    return lerp(moonColor(f), sunColor(f), f.sunVisibility);
}

// Himmels-Ambient: Oberseiten bekommen mehr Himmel, Unterseiten Bodenreflex.
float3 ambientColor(FrameUniforms f, float3 n)
{
    float3 sky = toLinear(f.skyColor);
    float3 dayUp = lerp(float3(0.40, 0.55, 0.88), sky * 1.1, 0.4) * 0.55;
    float3 dusk = float3(0.50, 0.38, 0.42) * 0.35;
    float3 day = lerp(dusk, dayUp, smoothstep(0.0, 0.45, f.noonFactor));
    float3 night = float3(0.040, 0.055, 0.100) * (0.7 + 0.6 * f.moonBrightness);
    float3 amb = lerp(night, day, f.sunVisibility);
    amb = lerp(amb, float3(luma(amb)) * float3(0.85, 0.9, 1.0), f.rainFactor * 0.6);
    float up = n.y * 0.5 + 0.5;
    return amb * lerp(0.45, 1.0, up);
}

// Fackel-/Blocklicht: warm, weiche Kurve, nah am Licht deutlich heller.
static const float3 TORCH_COLOR = float3(1.00, 0.58, 0.28);

// Helligkeit aus Vanillas Blocklicht-Stufe (Farbe kommt separat, siehe Raytracing)
float blockLightLevel(float level)
{
    float l = saturate(level);
    return l * l * (0.55 + 0.45 * l * l) * 2.4;
}

float3 blockLightColor(float level)
{
    return TORCH_COLOR * blockLightLevel(level);
}

// Mindestlicht, damit Hoehlen nicht komplett schwarz sind
static const float3 MIN_LIGHT = float3(0.010, 0.011, 0.014);

// ---------- Schatten ----------
// Radiale Verzerrung: nahe am Spieler mehr Texel. Muss in Schatten-Raster
// und Sampling identisch sein.
static const float SHADOW_DISTORT = 0.86;
float2 distortShadow(float2 p)
{
    float d = length(p) * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
    return p / d;
}

// ---------- Himmel ----------
float hash13(float3 p)
{
    p = frac(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return frac((p.x + p.y) * p.z);
}

float3 skyGradient(FrameUniforms f, float3 dir)
{
    float h = dir.y;
    float3 sky = toLinear(f.skyColor);
    // Tag: tiefes Blau oben, helles Dunst-Blau am Horizont
    float3 zenith = lerp(float3(0.10, 0.26, 0.70), sky * 0.9, 0.35);
    float3 horizon = float3(0.62, 0.74, 0.92);
    float3 day = lerp(horizon, zenith, pow(saturate(h), 0.55));
    // Daemmerung: Horizont glueht in Sonnenrichtung orange
    float sunAz = saturate(dot(normalize(float3(dir.x, 0.0, dir.z) + 1e-4), normalize(float3(f.sunDir.x, 0.0, f.sunDir.z) + 1e-4)) * 0.5 + 0.5);
    float3 duskHorizon = lerp(float3(0.30, 0.25, 0.42), float3(1.25, 0.42, 0.10), pow(sunAz, 3.0));
    float3 duskZenith = float3(0.10, 0.13, 0.34);
    float3 dusk = lerp(duskHorizon, duskZenith, pow(saturate(h), 0.45));
    day = lerp(dusk, day, smoothstep(0.0, 0.4, f.noonFactor));
    // Nacht
    float3 night = lerp(float3(0.030, 0.040, 0.075), float3(0.004, 0.006, 0.016), pow(saturate(h), 0.6));
    float3 c = lerp(night, day, f.sunVisibility);
    // unter dem Horizont: dunkler Bodendunst
    c = lerp(c, c * 0.35, saturate(-h * 3.0));
    // Regen: grau, flach
    c = lerp(c, float3(luma(c)) * float3(0.9, 0.95, 1.05) * 0.8, f.rainFactor * 0.85);
    return c;
}

float3 skyRadiance(FrameUniforms f, float3 dir, bool withDisks)
{
    float3 c = skyGradient(f, dir);
    float3 sunD = normalize(f.sunDir);
    float cs = dot(dir, sunD);
    // Sonnen-Halo (breit + eng), vom Regen gedaempft
    float halo = pow(saturate(cs), 14.0) * 0.18 + pow(saturate(cs), 120.0) * 1.2;
    c += sunColor(f) * halo * (1.0 - f.rainFactor * 0.8) * saturate(f.sunVisibility * 1.4);
    if (withDisks)
    {
        c += sunColor(f) * smoothstep(0.99955, 0.99975, cs) * 30.0 * (1.0 - f.rainFactor);
        float cm = dot(dir, -sunD);
        float moonDisk = smoothstep(0.99935, 0.99955, cm);
        c += float3(0.75, 0.82, 1.0) * (moonDisk * (0.6 + 1.6 * f.moonBrightness) + pow(saturate(cm), 40.0) * 0.05 * f.moonBrightness)
             * (1.0 - f.rainFactor);
        // Sterne: Zellraster auf der Himmelskugel, leichtes Funkeln
        if (dir.y > 0.0 && f.sunVisibility < 0.6)
        {
            float3 cell = floor(dir * 180.0);
            float r = hash13(cell);
            float star = step(0.9978, r) * (0.5 + 0.5 * sin(f.time * (2.0 + r * 6.0) + r * 40.0));
            c += float3(0.8, 0.85, 1.0) * star * 0.9 * saturate(1.0 - f.sunVisibility / 0.6) * saturate(dir.y * 4.0) * (1.0 - f.rainFactor);
        }
    }
    return c;
}

// ---------- Fog (HDR) ----------
float3 applyFog(FrameUniforms f, float3 c, float3 dir, float dist, float3 skyCol)
{
    if (f.fogType == 1) // Unterwasser
    {
        float3 wc = toLinear(f.fogColor) * 0.6;
        return lerp(c, wc, 1.0 - exp(-dist * 0.075));
    }
    if (f.fogType == 2) // Lava
        return lerp(c, float3(0.9, 0.25, 0.03), 1.0 - exp(-dist * 1.2));
    if (f.fogType == 3) // Pulverschnee
        return lerp(c, float3(0.6, 0.65, 0.7), 1.0 - exp(-dist * 0.8));
    if (f.dimension != 0)
    {
        float t = saturate(dist / max(f.renderDistance, 16.0));
        return lerp(c, toLinear(f.fogColor), t * t * 0.9);
    }
    // Atmosphaerischer Dunst (in Hoehlen weg) + Border-Fog exakt in den Himmel
    float haze = min(1.0 - exp(-dist * (0.0005 + f.rainFactor * 0.004)), 0.6) * (1.0 - f.caveFactor);
    c = lerp(c, skyCol * 0.8, haze);
    float t = saturate((dist / max(f.renderDistance, 16.0) - 0.8) / 0.2);
    return lerp(c, skyCol, t * t * (3.0 - 2.0 * t));
}

// ---------- Tonemap ----------
// Filmische Kurve (Schulter weich, Fuss leicht angehoben) + dezente Saettigung.
float3 tonemap(float3 c, float exposure)
{
    c *= exposure;
    float3 x = max(c - 0.002, 0.0);
    float3 m = (x * (6.2 * x + 0.5)) / (x * (6.2 * x + 1.7) + 0.06); // Kurve liefert Gamma-Werte
    m = saturate(m);
    m = m * m * (3.0 - 2.0 * m) * 0.25 + m * 0.75;                // leichter S-Kontrast
    float l = luma(m);
    m = lerp(float3(l), m, 1.12);                                  // Saettigung
    return saturate(m);
}

#endif

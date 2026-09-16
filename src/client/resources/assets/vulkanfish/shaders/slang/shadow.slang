// shadow.slang – Schatten-Sampling (verzerrte Lichtraum-Map, stabiles 3x3-PCF
// mit Hardware-Vergleich). Von Deferred- und Wasser-Pass genutzt.
#ifndef VULKANFISH_SHADOW_SLANG
#define VULKANFISH_SHADOW_SLANG

#include "look.slang"

// Himmelszugang aus Vanillas Himmelslicht (0..1): wo es (fast) 0 ist, fuehrt kein Weg zum Himmel,
// also auch kein Sonnenstrahl. Sicherheitsnetz fuer Hoehlen, falls der Schattenkarte ein
// Schattenwerfer fehlt (Section nie gesehen, Chunk noch nicht gemesht, ausserhalb der Tiefe).
float skyAccess(float skyL)
{
    return smoothstep(0.02, 0.33, skyL);
}

// offsetDir: Normale (Akne-Schutz) bzw. Lichtrichtung bei Laub
float sampleShadowMap(Texture2D<float> map, SamplerComparisonState cmp, FrameUniforms f,
                      float3 wp, float3 offsetDir, float2 pixel)
{
    float dist = length(wp - f.camPos);
    // Normal-Offset gegen Akne, waechst mit Entfernung (Texel werden durch die Verzerrung groesser)
    float bias = 0.03 + dist / f.shadowDistance * 0.12;
    float4 sc = mul(f.shadowViewProj, float4(wp + offsetDir * bias, 1.0));
    float2 center = sc.xy;
    float2 uv = distortShadow(center) * 0.5 + 0.5;
    if (any(uv <= 0.0) || any(uv >= 1.0) || sc.z >= 1.0)
        return 1.0;
    uint sw, sh, levels;
    map.GetDimensions(0, sw, sh, levels);
    // Zeitlich stabiles PCF ohne Rauschen: Kreuz aus 5 Hardware-Vergleichen (je 2x2
    // bilinear) im Abstand von 2,0 Texeln (~Weichheit des frueheren 3x3 zu 9-Tap-Kosten
    // von 5). Mit TAA-Akkumulation visuell identisch, bei Sonne in Bewegung ruhiger.
    float2 texel = 1.0 / float2(sw, sh);
    // Slope-skalierter Tiefen-Bias: streifende Flaechen (ndl klein) brauchen mehr Abstand
    // als zugewandte, sonst oszillieren sie bei wandernder Sonne zwischen Licht und Schatten
    // (Akne-Flackern auf Blockseiten). Laub (offsetDir = Lichtrichtung) bekommt keinen extra.
    float lightFade = length(f.lightDir);
    float slope = 1.0 - abs(dot(normalize(offsetDir + 1e-6), f.lightDir / max(lightFade, 1e-4)));
    float ref = sc.z - 0.0004 - saturate(slope) * 0.002;
    float2 tap = texel * 2.0; // breiteres Kreuz (~3x3-Weichheit) gegen Kanten-Kriechen bei Sonne in Bewegung
    float centerTap = map.SampleCmpLevelZero(cmp, uv, ref) * 4.0;
    float arms = map.SampleCmpLevelZero(cmp, uv + float2(1.0, 0.0) * tap, ref)
               + map.SampleCmpLevelZero(cmp, uv - float2(1.0, 0.0) * tap, ref)
               + map.SampleCmpLevelZero(cmp, uv + float2(0.0, 1.0) * tap, ref)
               + map.SampleCmpLevelZero(cmp, uv - float2(0.0, 1.0) * tap, ref);
    float lit = (centerTap + arms * 2.0) / 12.0;
    // Rand der Schattendistanz weich ausblenden
    return lerp(lit, 1.0, saturate((length(center) - 0.85) / 0.15));
}

#endif

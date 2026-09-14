// rr_guide.slang – Hilfspuffer fuer DLSS Ray Reconstruction (eigene Implementierung): aus dem
// G-Buffer diffuse Albedo (linear), spekulare Albedo und Welt-Normale + Rauheit. RR verlangt HDR-
// Farbe: das fertige LDR-Bild wird mit der Umkehrung unserer Tonemap-Kurve (look.slang) zurueck
// in Szenenlicht gerechnet – dann gilt Farbe = Albedo x Licht wie im Deferred-Pass, RR kann die
// Albedo sauber herausrechnen. rrResolve wendet die Kurve danach wieder an. Pixel, die nicht
// aus dem G-Buffer stammen (Vanillas Entities, Wasser, Glas, Partikel, Himmel), bekommen ihre
// Endfarbe als Albedo und eine Normale zur Kamera: RR sieht dort flaches Licht und glaettet nur
// zeitlich wie DLAA, statt fremde Albedo herauszurechnen.
#include "look.slang"

[[vk::binding(0, 0)]] Texture2D<float4> gAlbedoMat;
[[vk::binding(1, 0)]] Texture2D<float4> gNormalLight;
[[vk::binding(2, 0)]] Texture2D<float> gGDepth;     // Tiefe des G-Buffers (eigenes Terrain)
[[vk::binding(3, 0)]] Texture2D<float> gMainDepth;  // Mojangs Tiefe (inkl. Entities, Wasser)
[[vk::binding(4, 0)]] Texture2D<float4> gColor;     // Endbild vor der Kantenglaettung
[[vk::binding(5, 0)]] [format("rgba8")] RWTexture2D<float4> gDiffuse;
[[vk::binding(6, 0)]] [format("rgba8")] RWTexture2D<float4> gSpecular;
[[vk::binding(7, 0)]] [format("rgba16f")] RWTexture2D<float4> gNormals;

struct GuideUniforms
{
    float4x4 invViewProj; // Tiefe -> relativer Raum (Blickrichtung fuer Nicht-G-Buffer-Pixel)
    float2 screenSize;
    float exposure;
    float pad;
    float4 camPos;
};
[[vk::binding(8, 0)]] ConstantBuffer<GuideUniforms> gU;
[[vk::binding(9, 0)]] [format("rgba16f")] RWTexture2D<float4> gHdrColor;

// Umkehrung von tonemap(): Saettigung (luma bleibt), S-Kontrast (Newton), Filmkurve (quadratisch)
float3 rrExpand(float3 ldr, float exposure)
{
    float3 m = saturate(ldr);
    float l = luma(m);
    m = saturate(l + (m - l) / 1.12);
    float3 s = m;
    for (int i = 0; i < 4; i++) // m = s^2 (3 - 2s) / 4 + 0.75 s, monoton
    {
        float3 fs = s * s * (3.0 - 2.0 * s) * 0.25 + s * 0.75 - m;
        float3 ds = s * (1.0 - s) * 1.5 + 0.75;
        s = saturate(s - fs / ds);
    }
    s = min(s, 0.985); // 1.0 waere unendlich hell
    // s (6.2 x^2 + 1.7 x + 0.06) = 6.2 x^2 + 0.5 x  ->  a x^2 + b x + c = 0
    float3 a = 6.2 * (s - 1.0), b = 1.7 * s - 0.5, c = 0.06 * s;
    float3 x = (-b - sqrt(max(b * b - 4.0 * a * c, 0.0))) / (2.0 * a);
    return (max(x, 0.0) + 0.002 * step(1e-4, s)) / max(exposure, 1e-4);
}
float3 rrCompress(float3 hdr, float exposure) { return tonemap(max(hdr, 0.0), exposure); }

[shader("compute")]
[NumThreads(8, 8, 1)]
void rrGuide(in uint3 tid : SV_DispatchThreadID)
{
    int2 p = int2(tid.xy);
    if (p.x >= int(gU.screenSize.x) || p.y >= int(gU.screenSize.y))
        return;
    float3 c = gColor.Load(int3(p, 0)).rgb;
    gHdrColor[p] = float4(rrExpand(c, gU.exposure), 1.0);
    float dm = gMainDepth.Load(int3(p, 0));
    float dg = gGDepth.Load(int3(p, 0));
    // Reverse-Z: gleiche Flaeche -> fast gleiche Tiefe (relativ, auch in der Ferne)
    bool gbuf = dm > 0.0 && dg > 0.0 && abs(dm - dg) <= max(dm, dg) * 2e-3;
    if (gbuf)
    {
        float4 g0 = gAlbedoMat.Load(int3(p, 0));
        float4 g1 = gNormalLight.Load(int3(p, 0));
        gDiffuse[p] = float4(toLinear(g0.rgb), 1.0);
        gSpecular[p] = float4(0.04, 0.04, 0.04, 1.0); // Bloecke: diffus, dielektrischer Glanz
        gNormals[p] = float4(octDecode(g1.xy), 1.0);   // w = Rauheit (voll rau)
        return;
    }
    float2 uv = (float2(p) + 0.5) / gU.screenSize;
    float4 w = mul(gU.invViewProj, float4(uv * 2.0 - 1.0, max(dm, 1e-7), 1.0));
    float3 v = normalize(w.xyz / w.w - gU.camPos.xyz);
    gDiffuse[p] = float4(max(toLinear(c), 0.02), 1.0);
    gSpecular[p] = float4(0.0, 0.0, 0.0, 1.0);
    gNormals[p] = float4(-v, 1.0);
}

// RR-Ausgabe (HDR) zurueck ins LDR-Ziel der Kantenglaettung
[[vk::binding(0, 0)]] Texture2D<float4> gRrOut;
[[vk::binding(1, 0)]] [format("rgba8")] RWTexture2D<float4> gLdrOut;
[[vk::binding(2, 0)]] ConstantBuffer<GuideUniforms> gUR;

[shader("compute")]
[NumThreads(8, 8, 1)]
void rrResolve(in uint3 tid : SV_DispatchThreadID)
{
    uint w, h;
    gLdrOut.GetDimensions(w, h);
    if (tid.x >= w || tid.y >= h)
        return;
    gLdrOut[int2(tid.xy)] = float4(rrCompress(gRrOut.Load(int3(tid.xy, 0)).rgb, gUR.exposure), 1.0);
}

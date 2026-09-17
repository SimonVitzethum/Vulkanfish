// taa.slang – Temporales Anti-Aliasing (eigene Implementierung) ueber das fertige
// Level-Bild (Main-Target, Gamma) inkl. Vanillas Entities/Partikel, vor Hand + GUI.
//  - Reprojektion ueber Tiefe (naechster Tiefenwert im 3x3 -> saubere Kanten)
//  - History per Catmull-Rom (5 bilineare Taps), Varianz-Clipping in YCoCg
//  - Bewegungsabhaengige Mischrate, leichtes Nachschaerfen nur fuer die Ausgabe
[[vk::binding(0, 0)]] Texture2D<float4> gCurrent;
[[vk::binding(1, 0)]] Texture2D<float> gDepth;          // Reverse-Z
[[vk::binding(2, 0)]] Texture2D<float4> gHistory;
[[vk::binding(3, 0)]] [format("rgba16f")] RWTexture2D<float4> gHistoryOut;
[[vk::binding(4, 0)]] [format("rgba8")] RWTexture2D<float4> gOut;
[[vk::binding(5, 0)]] SamplerState gLinear;

struct TaaUniforms
{
    float4x4 invViewProj;   // aktueller Frame (mit Jitter)
    float4x4 prevViewProj;  // Vorframe MIT dessen Jitter (History = gejitterter Vorframe!)
    float2 screenSize;
    uint historyValid;
    float sharpness;
};
[[vk::binding(6, 0)]] ConstantBuffer<TaaUniforms> gTaa;

float3 toYCoCg(float3 c)
{
    return float3(dot(c, float3(0.25, 0.5, 0.25)), dot(c, float3(0.5, 0.0, -0.5)), dot(c, float3(-0.25, 0.5, -0.25)));
}

float3 fromYCoCg(float3 c)
{
    return float3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

// Catmull-Rom mit 5 bilinearen Taps (Ecken weggelassen): schaerfer als bilinear.
// Als float4 (Farbe + Tiefe im Alpha): die Tiefe speist den Disocclusion-Test unten.
float4 sampleHistory4(float2 uv, float2 size)
{
    float2 pos = uv * size;
    float2 c = floor(pos - 0.5) + 0.5;
    float2 f = pos - c;
    float2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    float2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    float2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    float2 w3 = f * f * (-0.5 + 0.5 * f);
    float2 w12 = w1 + w2;
    float2 tc0 = (c - 1.0) / size;
    float2 tc3 = (c + 2.0) / size;
    float2 tc12 = (c + w2 / w12) / size;
    float4 r = gHistory.SampleLevel(gLinear, float2(tc12.x, tc0.y), 0) * (w12.x * w0.y)
             + gHistory.SampleLevel(gLinear, float2(tc0.x, tc12.y), 0) * (w0.x * w12.y)
             + gHistory.SampleLevel(gLinear, tc12, 0) * (w12.x * w12.y)
             + gHistory.SampleLevel(gLinear, float2(tc3.x, tc12.y), 0) * (w3.x * w12.y)
             + gHistory.SampleLevel(gLinear, float2(tc12.x, tc3.y), 0) * (w12.x * w3.y);
    float wsum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
    return max(r / wsum, 0.0);
}

[shader("compute")]
[NumThreads(8, 8, 1)]
void taaResolve(in uint3 tid : SV_DispatchThreadID)
{
    TaaUniforms u = gTaa;
    int2 size = int2(u.screenSize);
    int2 p = int2(tid.xy);
    if (p.x >= size.x || p.y >= size.y)
        return;

    // Nachbarschaft: Momente (YCoCg) + naechste Tiefe
    float3 cur = gCurrent.Load(int3(p, 0)).rgb;
    float3 m1 = float3(0.0), m2 = float3(0.0), meanRgb = float3(0.0);
    float nearest = 0.0;
    int2 nearestP = p;
    for (int y = -1; y <= 1; y++)
    {
        for (int x = -1; x <= 1; x++)
        {
            int2 q = clamp(p + int2(x, y), int2(0, 0), size - 1);
            float3 c = gCurrent.Load(int3(q, 0)).rgb;
            float3 yc = toYCoCg(c);
            m1 += yc;
            m2 += yc * yc;
            meanRgb += c;
            float d = gDepth.Load(int3(q, 0));
            if (d > nearest) { nearest = d; nearestP = q; } // Reverse-Z: groesser = naeher
        }
    }
    m1 /= 9.0;
    m2 /= 9.0;
    meanRgb /= 9.0;
    float3 sigma = sqrt(max(m2 - m1 * m1, 0.0));

    // Reprojektion (Himmel: Tiefe 0 = Far-Plane, rotiert korrekt mit)
    float2 uv = (float2(p) + 0.5) / u.screenSize;
    float4 w = mul(u.invViewProj, float4(uv * 2.0 - 1.0, nearest, 1.0));
    w /= w.w;
    float4 pc = mul(u.prevViewProj, w);
    float2 prevUv = (pc.xy / pc.w) * 0.5 + 0.5;
    float motionPx = length((prevUv - uv) * u.screenSize);

    float3 result = cur;
    // Rand-Clamp statt Verwerfen: ausserhalb projizierte Pixel bekamen bisher gar keine
    // History und zeigten das rohe Jitter-Bild (flimmernder Bildschirmrand). Geklemmte
    // History (Kante gestreckt) ist stabil; das Varianz-Clipping verwirft sie bei Bedarf.
    if (u.historyValid != 0 && pc.w > 0.0)
    {
        float2 cuv = clamp(prevUv, 0.0, 1.0);
        float4 hist4 = sampleHistory4(cuv, u.screenSize);
        float3 hist = toYCoCg(hist4.rgb);
        // Disocclusion-Test ueber die mitgespeicherte Vorframe-Tiefe (Reverse-Z, relativ):
        // neu sichtbar Gewordenes bekommt Current (kein Ghosting), stabiles Vertrauen History.
        // Fixt auch duenne Silhouetten: falsch reprojizierte History faellt hier raus statt zu flackern.
        float disocc = saturate(abs(nearest - hist4.a) / max(max(nearest, hist4.a), 1e-4) * 20.0);
        // Varianz-Clipping: History auf die Farbverteilung der aktuellen Nachbarschaft ziehen
        float gamma = lerp(1.25, 0.9, saturate(motionPx / 12.0));
        float3 lo = m1 - gamma * sigma, hi = m1 + gamma * sigma;
        float3 center = (lo + hi) * 0.5, ext = max((hi - lo) * 0.5, 1e-4);
        float3 off = hist - center;
        float3 ratio = abs(off / ext);
        float maxR = max(ratio.x, max(ratio.y, ratio.z));
        if (maxR > 1.0) hist = center + off / maxR;
        float alpha = max(lerp(0.08, 0.3, saturate(motionPx / 10.0)), disocc);
        result = fromYCoCg(lerp(hist, toYCoCg(cur), alpha));
    }
    gHistoryOut[p] = float4(result, nearest);
    // Nachschaerfen nur fuer die Anzeige (nicht in die History: sonst Ringing-Aufschaukeln)
    float3 sharp = result + (result - meanRgb) * u.sharpness;
    gOut[p] = float4(saturate(sharp), 1.0);
}

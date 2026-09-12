// oit.slang – exakte Transparenz fuer Glas/Eis (eigene Implementierung).
// Die Fragmente liegen als Pro-Pixel-Listen vor (water.slang: translucentRecordFrag).
// oitResolve sortiert jede Liste nach Tiefe und teilt sie an der Wasseroberflaeche:
//  - Schichten HINTER dem Wasser werden in die Szenen-Kopie komponiert, die der
//    Wasser-Pass fuer Brechung/SSR liest (Glas unter Wasser bleibt sichtbar)
//  - Schichten DAVOR werden vormultipliziert (rgb, Resttransmission) abgelegt und
//    nach dem Wasser per Fullscreen-Draw ueber das Main-Target geblendet.
[[vk::binding(0, 0)]] [format("r32ui")] RWTexture2D<uint> gHead;
[[vk::binding(1, 0)]] StructuredBuffer<uint> gNodes;
[[vk::binding(2, 0)]] Texture2D<float> gWaterDepth;   // Main-Tiefe nach dem Wasser-Tiefen-Vorpass
[[vk::binding(3, 0)]] Texture2D<float> gSceneDepth;   // Main-Tiefe davor (Terrain + Entities)
[[vk::binding(4, 0)]] [format("rgba8")] RWTexture2D<float4> gSceneColor;
[[vk::binding(5, 0)]] [format("rgba16f")] RWTexture2D<float4> gFront;
[[vk::binding(6, 0)]] StructuredBuffer<uint> gCounter; // [1] Kapazitaet
[[vk::binding(7, 0)]] [format("r32f")] RWTexture2D<float> gFrontDepth; // naechste Schicht davor (0 = keine)

static const uint MAX_LAYERS = 16;     // die naechsten 16 Schichten zaehlen, der Rest ist verdeckt genug
static const uint MAX_WALK = 256;      // Schutz gegen kaputte Listen
static const uint END = 0xFFFFFFFFu;

struct OitPush
{
    uint2 size;
};
[[vk::push_constant]] ConstantBuffer<OitPush> gPush;

float4 unpackRgba8(uint p)
{
    return float4(float(p & 0xFF), float((p >> 8) & 0xFF), float((p >> 16) & 0xFF), float(p >> 24)) / 255.0;
}

// Reverse-Z: groesser = naeher. Gleiche Tiefe -> nach Farbe, damit das Ergebnis
// unabhaengig von der Einfuege-Reihenfolge ist (deckungsgleiche Flaechen flackern nicht).
bool farther(float da, uint ca, float db, uint cb)
{
    return da < db || (da == db && ca < cb);
}

[shader("compute")]
[NumThreads(8, 8, 1)]
void oitResolve(in uint3 tid : SV_DispatchThreadID)
{
    int2 p = int2(tid.xy);
    if (tid.x >= gPush.size.x || tid.y >= gPush.size.y)
        return;
    uint idx = gHead[p];
    if (idx == END)
    {
        gFront[p] = float4(0.0, 0.0, 0.0, 1.0);
        gFrontDepth[p] = 0.0;
        return;
    }

    // Die naechsten MAX_LAYERS Schichten aufsteigend (fern -> nah) einsortieren
    float depth[MAX_LAYERS];
    uint color[MAX_LAYERS];
    uint n = 0;
    uint cap = gCounter[1];
    for (uint w = 0; w < MAX_WALK && idx != END && idx < cap; w++)
    {
        uint c = gNodes[idx * 3];
        float d = asfloat(gNodes[idx * 3 + 1]);
        idx = gNodes[idx * 3 + 2];
        uint pos;
        if (n < MAX_LAYERS)
        {
            pos = n++;
        }
        else
        {
            if (farther(d, c, depth[0], color[0]))
                continue; // ferner als alles Behaltene
            // fernste Schicht verwerfen, Rest nachruecken
            for (uint k = 1; k < MAX_LAYERS; k++)
            {
                depth[k - 1] = depth[k];
                color[k - 1] = color[k];
            }
            pos = MAX_LAYERS - 1;
        }
        while (pos > 0 && farther(d, c, depth[pos - 1], color[pos - 1]))
        {
            depth[pos] = depth[pos - 1];
            color[pos] = color[pos - 1];
            pos--;
        }
        depth[pos] = d;
        color[pos] = c;
    }

    // Wasser vor der Szene? Dann trennt seine Oberflaeche "dahinter" von "davor".
    // Schichten in (fast) derselben Ebene wie das Wasser zaehlen als davor.
    float wd = gWaterDepth.Load(int3(p, 0));
    bool water = wd > gSceneDepth.Load(int3(p, 0));
    float split = water ? wd / 1.001 : 0.0;

    float3 scene = gSceneColor[p].rgb;
    float3 front = float3(0.0);
    float trans = 1.0;
    float nearest = 0.0;
    for (uint i = 0; i < n; i++)
    {
        float4 c = unpackRgba8(color[i]);
        if (depth[i] < split)
        {
            scene = lerp(scene, c.rgb, c.a);
        }
        else
        {
            front = lerp(front, c.rgb, c.a);
            trans *= 1.0 - c.a;
            nearest = max(nearest, depth[i]);
            // Ohne Wasser: Glas auch in die Szenen-Kopie, damit Wasser-SSR es spiegelt
            if (!water)
                scene = lerp(scene, c.rgb, c.a);
        }
    }
    gSceneColor[p] = float4(scene, 1.0);
    gFront[p] = float4(front, trans);
    gFrontDepth[p] = nearest;
}

// ---------- Fullscreen-Composite (nach dem Wasser, gleicher Rendering-Scope) ----------
[[vk::binding(0, 0)]] Texture2D<float4> gFrontIn;
[[vk::binding(1, 0)]] Texture2D<float> gFrontDepthIn;

struct FsOut
{
    float4 pos : SV_Position;
};

[shader("mesh")]
[NumThreads(1, 1, 1)]
[outputtopology("triangle")]
void oitFullscreenMesh(out vertices FsOut verts[3], out indices uint3 tris[1])
{
    SetMeshOutputCounts(3, 1);
    verts[0].pos = float4(-1.0, -1.0, 0.0, 1.0);
    verts[1].pos = float4(3.0, -1.0, 0.0, 1.0);
    verts[2].pos = float4(-1.0, 3.0, 0.0, 1.0);
    tris[0] = uint3(0, 1, 2);
}

// Blend: dst = src.rgb + dst.rgb * src.a  (vormultiplizierte Schichten ueber die Szene)
struct CompositeOut
{
    float4 color : SV_Target0;
    float depth : SV_Depth;   // naechste Glasschicht -> Main-Tiefe (wie Vanillas transluzentes Terrain)
};

[shader("fragment")]
CompositeOut oitCompositeFrag(FsOut input)
{
    int2 p = int2(input.pos.xy);
    float4 v = gFrontIn.Load(int3(p, 0));
    if (v.a >= 0.9999)
        discard;
    CompositeOut o;
    o.color = v;
    o.depth = gFrontDepthIn.Load(int3(p, 0));
    return o;
}

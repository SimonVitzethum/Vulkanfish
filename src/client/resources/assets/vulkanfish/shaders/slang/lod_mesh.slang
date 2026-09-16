// lod_mesh.slang – Fernfeld (LOD-Voxel): ein Meshlet = bis zu 32 Quads a 8 Byte, der
// Mesh-Shader spannt daraus 4 Vertices / 2 Dreiecke je Quad auf und schreibt in denselben
// G-Buffer wie das Nahfeld -> identisches Licht, Nebel, Himmel (gleicher Look).
// Quads in vom Nahfeld gezeichneten Chunks werden verworfen (Abdeckungsmaske).
#include "gpu_scene.slang"
#include "look.slang"

[[vk::binding(0, 0)]] StructuredBuffer<MeshletHeader> gMeshlets; // vertexStart = erstes Quad, indexStart = Stufe
[[vk::binding(1, 0)]] StructuredBuffer<uint2> gQuads;
[[vk::binding(2, 0)]] StructuredBuffer<uint> gNearMask;          // je Chunk (& 127, toroidal) Section-Bits ab minSectionY
[[vk::binding(3, 0)]] ConstantBuffer<FrameUniforms> gFrame;
[[vk::binding(4, 0)]] Texture2D<float4> gAtlas;                  // Vanillas Block-Atlas (wie das Nahfeld)
[[vk::binding(5, 0)]] SamplerState gAtlasSamp;
[[vk::binding(6, 0)]] StructuredBuffer<uint> gVisibleMeshlets;
// je Aussehen 3 Seiten (oben, Seite, unten): uv0, uv1 (unorm16), Farbe | Toenung << 24, feste Toenung | Helligkeit << 24
[[vk::binding(7, 0)]] StructuredBuffer<uint4> gLook;
[[vk::binding(8, 0)]] StructuredBuffer<uint4> gBiomeCol;         // Gras, Laub, Trockenlaub, Wasser

struct LodPush
{
    int2 camChunk;
    int maskValid;
    int minSectionY;
};
[[vk::push_constant]] ConstantBuffer<LodPush> gLod;

static const uint LOD_KIND_LEAVES = 2;
static const uint LOD_KIND_WATER = 4;
static const uint LOD_KIND_LAVA = 5;

struct LodVertOut
{
    float4 pos : SV_Position;
    nointerpolation float3 color;  // Gamma
    nointerpolation float3 normal;
    nointerpolation float2 light;  // Emission, Himmel (0..1)
    nointerpolation uint mat;      // Material | Emission << 4 (wie das Nahfeld)
    float3 inner : TEXCOORD5;      // Punkt im Voxel hinter der Flaeche (Abdeckungstest je Pixel)
    float2 uvw : TEXCOORD6;        // Texturkoordinate in Bloecken (je Block ein Sprite, wie Vanilla)
    nointerpolation float4 rect : TEXCOORD7; // Sprite im Atlas (u0, v0, u1, v1), leer = keine Textur
    nointerpolation float spriteLuma : TEXCOORD8;
};

struct LodPrimOut
{
    bool cull : SV_CullPrimitive;
};

uint mulRgb(uint a, uint b)
{
    uint r = ((a >> 16) & 255u) * ((b >> 16) & 255u) / 255u;
    uint g = ((a >> 8) & 255u) * ((b >> 8) & 255u) / 255u;
    uint bl = (a & 255u) * (b & 255u) / 255u;
    return (r << 16) | (g << 8) | bl;
}

// Durchschnittsfarbe der Seite inkl. Biom-Toenung (wie LodMaterials.faceColor)
uint lookColor(uint4 face, uint biome)
{
    uint c = face.z & 0xFFFFFFu, tint = face.z >> 24;
    if (tint == 0)
        return c;
    uint4 bc = gBiomeCol[biome];
    uint t = tint == 1 ? bc.x : tint == 2 ? bc.y : tint == 3 ? bc.z : tint == 4 ? bc.w : face.w;
    return mulRgb(c, t & 0xFFFFFFu);
}

static const float3 FACE_N[6] = { float3(1, 0, 0), float3(-1, 0, 0), float3(0, 1, 0),
                                  float3(0, -1, 0), float3(0, 0, 1), float3(0, 0, -1) };

// Vom Nahfeld gezeichnete Sections des Chunks (0 = nichts / ausserhalb des Fensters)
uint nearBits(int2 c)
{
    if (gLod.maskValid == 0 || any(abs(c - gLod.camChunk) >= 64))
        return 0u;
    return gNearMask[uint((c.y & 127) * 128 + (c.x & 127))];
}

// Section-Bits von y0..y1 (Bloecke); unter/ueber dem Bereich liegt nichts -> gilt als abgedeckt
uint sectionRange(float y0, float y1) // y relativ
{
    int s0 = max(worldSectionY(gFrame, y0) - gLod.minSectionY, 0);
    int s1 = min(worldSectionY(gFrame, y1) - gLod.minSectionY, 31);
    if (s1 < s0)
        return 0u;
    uint hi = s1 >= 31 ? 0xFFFFFFFFu : ((1u << uint(s1 + 1)) - 1u);
    return hi & ~((1u << uint(s0)) - 1u);
}

// Box komplett in gezeichneten Sections? (Quad samt dahinterliegendem Voxel)
bool nearCoveredBox(float3 mn, float3 mx)
{
    if (gLod.maskValid == 0)
        return false;
    int2 c0 = worldChunk(gFrame, mn.xz), c1 = worldChunk(gFrame, mx.xz); // relativ -> Welt-Chunks
    if (any(c1 - c0 > 3))
        return false;
    uint need = sectionRange(mn.y, mx.y);
    for (int z = c0.y; z <= c1.y; z++)
        for (int x = c0.x; x <= c1.x; x++)
            if ((nearBits(int2(x, z)) & need) != need)
                return false;
    return true;
}

// Punkt in einer gezeichneten Section?
bool nearCoveredPoint(float3 p)
{
    uint b = nearBits(worldChunk(gFrame, p.xz)); // p relativ
    int s = worldSectionY(gFrame, p.y) - gLod.minSectionY;
    return s >= 0 && s < 32 && ((b >> uint(s)) & 1u) != 0;
}

[shader("mesh")]
[NumThreads(32, 1, 1)]
[outputtopology("triangle")]
void lodMesh(
    in uint3 gid : SV_GroupID,
    in uint3 ltid : SV_GroupThreadID,
    out vertices LodVertOut verts[128],
    out indices uint3 tris[64],
    out primitives LodPrimOut prims[64])
{
    uint lt = ltid.x;
    MeshletHeader mh = gMeshlets[gVisibleMeshlets[gid.x]];
    FrameUniforms f = gFrame;
    uint qCount = min(mh.vertexCount, 32);
    SetMeshOutputCounts(qCount * 4, qCount * 2);
    if (lt >= qCount)
        return;
    uint2 q = gQuads[mh.vertexStart + lt];
    uint level = mh.indexStart;
    float scale = float(1u << level);
    float3 origin = relOrigin(f, mh.originX, mh.originY, mh.originZ);

    uint px = q.x & 31, pz = (q.x >> 5) & 31, py = (q.x >> 10) & 511;
    uint face = (q.x >> 19) & 7;
    float su = float(((q.x >> 22) & 31) + 1), sv = float(((q.x >> 27) & 31) + 1);
    uint axis = face >> 1;
    bool positive = (face & 1) == 0;

    // Ebene auf der Aussenseite des Voxels, Rechteck in (u, v)
    float3 base = float3(px, py, pz);
    float3 du, dv;
    if (axis == 0) { if (positive) base.x += 1.0; du = float3(0, 0, su); dv = float3(0, sv, 0); }
    else if (axis == 1) { if (positive) base.y += 1.0; du = float3(su, 0, 0); dv = float3(0, 0, sv); }
    else { if (positive) base.z += 1.0; du = float3(su, 0, 0); dv = float3(0, sv, 0); }
    float3 p0 = origin + base * scale;
    du *= scale;
    dv *= scale;

    uint texId = q.y & 0x1FFFu, biome = (q.y >> 13) & 0xFFu;
    uint fsel = face == 2 ? 0u : face == 3 ? 2u : 1u;
    uint4 look = gLook[texId * 3 + fsel];
    uint rgb = lookColor(look, biome);
    float3 color = float3(float((rgb >> 16) & 255u), float((rgb >> 8) & 255u), float(rgb & 255u)) / 255.0;
    float4 rect = float4(float(look.x & 0xFFFFu), float(look.x >> 16), float(look.y & 0xFFFFu), float(look.y >> 16)) / 65535.0;
    float sky = float((q.y >> 21) & 0xF) / 15.0;
    uint emission = (q.y >> 25) & 0xF;
    uint kind = (q.y >> 29) & 0x7;
    uint mat = kind == LOD_KIND_LEAVES ? MAT_LEAVES : kind == LOD_KIND_LAVA ? MAT_LAVA : kind == LOD_KIND_WATER ? MAT_WATER : MAT_DEFAULT;
    float3 n = FACE_N[face];

    float3 corners[4] = { p0, p0 + du, p0 + du + dv, p0 + dv };
    float3 back = -n * min(0.5, scale * 0.5); // in den Voxel, dem die Flaeche gehoert
    for (uint c = 0; c < 4; c++)
    {
        uint vi = lt * 4 + c;
        float4 clip = mul(f.viewProj, float4(corners[c], 1.0));
        // LOD immer einen Hauch hinter das Nahfeld: wo beide zeichnen (Maske
        // veraltet, Chunk-Grenze), gewinnt das Nahfeld per Tiefentest statt
        // Z-Fighting/Flackern. 1.5e-4 NDC ist subpixel, aber > 24-bit-Tiefenrauschen.
        // Reverse-Z: groesser = naeher, also z verkleinern = weiter weg.
        clip.z -= 1.5e-4 * clip.w;
        verts[vi].pos = clip;
        verts[vi].inner = corners[c] + back;
        // UV wie Vanillas Blockflaechen: Seiten mit der Texturoberkante oben, Osten/Norden gespiegelt
        float3 w = corners[c];
        float2 uv = face == 0 ? float2(-w.z, -w.y) : face == 1 ? float2(w.z, -w.y)
                  : face == 2 ? float2(w.x, w.z) : face == 3 ? float2(w.x, -w.z)
                  : face == 4 ? float2(w.x, -w.y) : float2(-w.x, -w.y);
        verts[vi].uvw = uv;
        verts[vi].rect = rect;
        verts[vi].spriteLuma = float(look.w >> 24) / 255.0;
        verts[vi].color = color;
        verts[vi].normal = n;
        verts[vi].light = float2(float(emission) / 15.0, sky);
        verts[vi].mat = mat | (emission << 4);
    }
    uint b = lt * 4;
    tris[lt * 2] = uint3(b, b + 1, b + 2);
    tris[lt * 2 + 1] = uint3(b, b + 2, b + 3);
    // ganz vom Nahfeld gezeichnet -> verwerfen; teilweise -> je Pixel im Fragment-Shader
    float3 qa = p0 - n * scale, qb = p0 + du + dv;
    float3 qmn = min(qa, qb) + 0.01, qmx = max(qa, qb) - 0.01;
    bool cull = nearCoveredBox(qmn, qmx);
    prims[lt * 2].cull = cull;
    prims[lt * 2 + 1].cull = cull;
}

// ---------- Schatten-Pass: Fernfeld wirft Schatten, wo das Nahfeld nichts zeichnet ----------
// Rueckfallebene: das Nahfeld laedt im Schattenradius alle Schattenwerfer selbst, aber bis sie
// gemesht sind (oder unter der Kamera, ausserhalb des Radius) fuellt das LOD die Luecken
// (Abdeckungsmaske). gFrame ist hier der Schatten-UBO (Ortho-Lichtraum).
// Die radiale Verzerrung wirkt je Vertex: grosse Greedy-Quads wuerden krumm (Risse, verschobene
// Schatten) -> jedes Quad in bis zu 4x4 Stuecke teilen. Eine Workgroup je Meshlet haette dafuer zu
// wenig Ausgabeplatz: Indirect-y = 4, Gruppe y zeichnet Quads y*8 .. y*8+7.
struct LodShadowVertOut
{
    float4 pos : SV_Position;
    float3 inner : TEXCOORD5;
};

static const uint SH_QUADS = 8;   // Quads je Workgroup
static const uint SH_GRID = 4;    // max. Teilung je Achse
static const uint SH_VERTS = (SH_GRID + 1) * (SH_GRID + 1);
static const uint SH_TRIS = SH_GRID * SH_GRID * 2;
groupshared float3 sShP0[SH_QUADS], sShDu[SH_QUADS], sShDv[SH_QUADS], sShBack[SH_QUADS];
groupshared uint sShN[SH_QUADS];   // Teilung, 0 = verworfen

[shader("mesh")]
[NumThreads(32, 1, 1)]
[outputtopology("triangle")]
void lodShadowMesh(
    in uint3 gid : SV_GroupID,
    in uint3 ltid : SV_GroupThreadID,
    out vertices LodShadowVertOut verts[SH_QUADS * SH_VERTS],
    out indices uint3 tris[SH_QUADS * SH_TRIS],
    out primitives LodPrimOut prims[SH_QUADS * SH_TRIS])
{
    uint lt = ltid.x;
    MeshletHeader mh = gMeshlets[gVisibleMeshlets[gid.x]];
    FrameUniforms f = gFrame;
    uint first = gid.y * SH_QUADS;
    uint total = min(mh.vertexCount, 32);
    uint qCount = first < total ? min(total - first, SH_QUADS) : 0;
    SetMeshOutputCounts(qCount * SH_VERTS, qCount * SH_TRIS);
    if (qCount == 0)
        return;
    if (lt < qCount)
    {
        uint2 q = gQuads[mh.vertexStart + first + lt];
        float scale = float(1u << mh.indexStart);
        float3 origin = relOrigin(f, mh.originX, mh.originY, mh.originZ);
        uint px = q.x & 31, pz = (q.x >> 5) & 31, py = (q.x >> 10) & 511;
        uint face = (q.x >> 19) & 7;
        float su = float(((q.x >> 22) & 31) + 1), sv = float(((q.x >> 27) & 31) + 1);
        uint axis = face >> 1;
        bool positive = (face & 1) == 0;
        float3 base = float3(px, py, pz);
        float3 du, dv;
        if (axis == 0) { if (positive) base.x += 1.0; du = float3(0, 0, su); dv = float3(0, sv, 0); }
        else if (axis == 1) { if (positive) base.y += 1.0; du = float3(su, 0, 0); dv = float3(0, 0, sv); }
        else { if (positive) base.z += 1.0; du = float3(su, 0, 0); dv = float3(0, sv, 0); }
        float3 p0 = origin + base * scale;
        du *= scale;
        dv *= scale;
        float3 n = FACE_N[face];
        // Wasser wirft keinen Schatten (wie im Nahfeld); ganz vom Nahfeld gezeichnet -> dessen Geometrie gilt
        uint kind = (q.y >> 29) & 0x7;
        float3 qa = p0 - n * scale, qb = p0 + du + dv;
        bool cull = kind == LOD_KIND_WATER || nearCoveredBox(min(qa, qb) + 0.01, max(qa, qb) - 0.01);
        // Stuecke hoechstens ~8 Bloecke; Rand leicht ueberlappen (Risse an T-Stoessen)
        float3 eu = normalize(du) * 0.03, ev = normalize(dv) * 0.03;
        sShP0[lt] = p0 - eu - ev;
        sShDu[lt] = du + eu * 2.0;
        sShDv[lt] = dv + ev * 2.0;
        sShBack[lt] = -n * min(0.5, scale * 0.5);
        sShN[lt] = cull ? 0u : uint(clamp(ceil(max(length(du), length(dv)) / 8.0), 1.0, float(SH_GRID)));
    }
    GroupMemoryBarrierWithGroupSync();
    for (uint i = lt; i < qCount * SH_VERTS; i += 32)
    {
        uint k = i / SH_VERTS, v = i % SH_VERTS;
        float nq = float(max(sShN[k], 1u));
        float2 g = min(float2(v % (SH_GRID + 1), v / (SH_GRID + 1)), nq) / nq;
        float3 w = sShP0[k] + sShDu[k] * g.x + sShDv[k] * g.y;
        float4 clip = mul(f.viewProj, float4(w, 1.0));
        clip.xy = distortShadow(clip.xy);
        verts[i].pos = clip;
        verts[i].inner = w + sShBack[k];
    }
    for (uint i = lt; i < qCount * SH_TRIS; i += 32)
    {
        uint k = i / SH_TRIS, t = i % SH_TRIS, c = t >> 1;
        uint cu = c % SH_GRID, cv = c / SH_GRID, nq = sShN[k];
        uint v00 = k * SH_VERTS + cv * (SH_GRID + 1) + cu;
        tris[i] = (t & 1) == 0 ? uint3(v00, v00 + 1, v00 + SH_GRID + 2) : uint3(v00, v00 + SH_GRID + 2, v00 + SH_GRID + 1);
        prims[i].cull = cu >= nq || cv >= nq;
    }
}

struct LodShadowFragIn
{
    float4 pos : SV_Position;
    float3 inner : TEXCOORD5;
};

[shader("fragment")]
void lodShadowFrag(LodShadowFragIn input)
{
    if (nearCoveredPoint(input.inner))
        discard;
}

struct LodFragIn
{
    float4 pos : SV_Position;
    nointerpolation float3 color;
    nointerpolation float3 normal;
    nointerpolation float2 light;
    nointerpolation uint mat;
    float3 inner : TEXCOORD5;
    float2 uvw : TEXCOORD6;
    nointerpolation float4 rect : TEXCOORD7;
    nointerpolation float spriteLuma : TEXCOORD8;
};

struct LodGBufferOut
{
    float4 albedoMat : SV_Target0;
    float4 normalLight : SV_Target1;
};

[shader("fragment")]
LodGBufferOut lodFrag(LodFragIn input)
{
    // Blocktextur als Muster um die exakte Durchschnittsfarbe (Helligkeitsverhaeltnis): nah am
    // Nahfeld dieselbe Koernung, in der Ferne waehlt die Mip-Stufe von selbst den Mittelwert
    float3 color = input.color;
    float2 size = input.rect.zw - input.rect.xy;
    // Gradienten der durchgehenden Koordinate (frac springt an Blockgrenzen -> sonst Mip-Naehte);
    // ausserhalb der Verzweigung (Ableitungen brauchen alle Nachbarpixel)
    float2 dx = ddx(input.uvw) * size, dy = ddy(input.uvw) * size;
    if (size.x > 0.0 && input.spriteLuma > 0.01)
    {
        float2 f = frac(input.uvw);
        float4 t = gAtlas.SampleGrad(gAtlasSamp, input.rect.xy + f * size, dx, dy);
        float l = dot(t.rgb, float3(0.2126, 0.7152, 0.0722));
        l = lerp(input.spriteLuma * 0.55, l, saturate(t.a * 2.0 - 0.5)); // Loecher (Laub): dunkler Innenraum
        color *= clamp(l / input.spriteLuma, 0.0, 2.5);
    }
    // Pixel in einer vom Nahfeld gezeichneten Section: dort gilt das Nahfeld (keine Doppelflaechen,
    // keine Loecher an Chunk-Grenzen, die ein Quad nur teilweise ueberdeckt). Nach den Ableitungen.
    if (nearCoveredPoint(input.inner))
        discard;
    if (gFrame.debugView == 10)
        color = float3(1.0, 0.0, 1.0); // Debug: vom Fernfeld gezeichnete Pixel magenta
    LodGBufferOut o;
    o.albedoMat = float4(saturate(color), float(input.mat) / 255.0);
    o.normalLight = float4(octEncode(input.normal), input.light.x, input.light.y);
    return o;
}

// ---------- Classic-Raster-Fallback (ohne Mesh-Shader) ----------
// Quad q (0..31) des sichtbaren Meshlets laden (Ebene, Rechteck). Draws: non-indexed
// Multi-DrawIndirectCount (ein Draw je Meshlet, firstInstance = Listenplatz), 6 Vertices
// je Quad ((0,1,2),(0,2,3)). Abdeckung -> degeneriertes Dreieck (Fragment verwirft
// zusaetzlich je Pixel wie im Mesh-Pfad) statt Primitive-Cull.
void lodQuad(uint iid, uint qq, out uint2 q, out uint level, out float scale, out float3 origin,
             out float3 p0, out float3 du, out float3 dv, out uint face, out float3 n)
{
    MeshletHeader mh = gMeshlets[gVisibleMeshlets[iid]];
    q = gQuads[mh.vertexStart + qq];
    level = mh.indexStart;
    scale = float(1u << level);
    origin = relOrigin(gFrame, mh.originX, mh.originY, mh.originZ);
    uint px = q.x & 31, pz = (q.x >> 5) & 31, py = (q.x >> 10) & 511;
    face = (q.x >> 19) & 7;
    float su = float(((q.x >> 22) & 31) + 1), sv = float(((q.x >> 27) & 31) + 1);
    uint axis = face >> 1;
    bool positive = (face & 1) == 0;
    float3 base = float3(px, py, pz);
    if (axis == 0) { if (positive) base.x += 1.0; du = float3(0, 0, su); dv = float3(0, sv, 0); }
    else if (axis == 1) { if (positive) base.y += 1.0; du = float3(su, 0, 0); dv = float3(0, 0, sv); }
    else { if (positive) base.z += 1.0; du = float3(su, 0, 0); dv = float3(0, sv, 0); }
    p0 = origin + base * scale;
    du *= scale;
    dv *= scale;
    n = FACE_N[face];
}

// Ecke (tri, k) -> (u, v) im Quad ((0,1,2),(0,2,3))
void lodQuadCorner(uint tri, uint k, out float u, out float v)
{
    uint idx = tri == 0 ? (k == 2 ? 2u : k) : (k == 0 ? 0u : (k == 1 ? 2u : 3u));
    u = (idx == 1 || idx == 2) ? 1.0 : 0.0;
    v = idx >= 2 ? 1.0 : 0.0;
}

[shader("vertex")]
LodVertOut lodClassicVert(uint vid : SV_VertexID, uint iid : SV_InstanceID)
{
    uint qq = vid / 6, tri = (vid % 6) / 3, k = vid % 3;
    uint2 q;
    uint level;
    float scale;
    float3 origin, p0, du, dv, n;
    uint face;
    lodQuad(iid, qq, q, level, scale, origin, p0, du, dv, face, n);
    float cu, cv;
    lodQuadCorner(tri, k, cu, cv);
    float3 w = p0 + du * cu + dv * cv;
    uint texId = q.y & 0x1FFFu, biome = (q.y >> 13) & 0xFFu;
    uint fsel = face == 2 ? 0u : face == 3 ? 2u : 1u;
    uint4 look = gLook[texId * 3 + fsel];
    uint rgb = lookColor(look, biome);
    float sky = float((q.y >> 21) & 0xF) / 15.0;
    uint emission = (q.y >> 25) & 0xF;
    uint kind = (q.y >> 29) & 0x7;
    float3 qa = p0 - n * scale, qb = p0 + du + dv;
    float3 qmn = min(qa, qb) + 0.01, qmx = max(qa, qb) - 0.01;
    bool cull = nearCoveredBox(qmn, qmx);
    float3 back = -n * min(0.5, scale * 0.5);
    LodVertOut o;
    float4 clip = mul(gFrame.viewProj, float4(w, 1.0));
    clip.z -= 1.5e-4 * clip.w; // LOD hinter Nahfeld (wie Mesh-Pfad)
    o.pos = cull ? float4(0, 0, 0, 1) : clip;
    o.inner = w + back;
    o.uvw = face == 0 ? float2(-w.z, -w.y) : face == 1 ? float2(w.z, -w.y)
              : face == 2 ? float2(w.x, w.z) : face == 3 ? float2(w.x, -w.z)
              : face == 4 ? float2(w.x, -w.y) : float2(-w.x, -w.y);
    o.rect = float4(float(look.x & 0xFFFFu), float(look.x >> 16), float(look.y & 0xFFFFu), float(look.y >> 16)) / 65535.0;
    o.spriteLuma = float(look.w >> 24) / 255.0;
    o.color = float3(float((rgb >> 16) & 255u), float((rgb >> 8) & 255u), float(rgb & 255u)) / 255.0;
    o.normal = n;
    o.light = float2(float(emission) / 15.0, sky);
    uint mat = kind == LOD_KIND_LEAVES ? MAT_LEAVES : kind == LOD_KIND_LAVA ? MAT_LAVA : kind == LOD_KIND_WATER ? MAT_WATER : MAT_DEFAULT;
    o.mat = mat | (emission << 4);
    return o;
}

// Schatten ohne Unterteilung (Fallback-Vereinfachung): ganze Quads, Slope-Bias der
// Pipeline faengt den Kruemmungsfehler auf. Nur LOD-Luecken des Nahfelds.
[shader("vertex")]
LodShadowVertOut lodShadowClassicVert(uint vid : SV_VertexID, uint iid : SV_InstanceID)
{
    uint qq = vid / 6, tri = (vid % 6) / 3, k = vid % 3;
    uint2 q;
    uint level;
    float scale;
    float3 origin, p0, du, dv, n;
    uint face;
    lodQuad(iid, qq, q, level, scale, origin, p0, du, dv, face, n);
    float cu, cv;
    lodQuadCorner(tri, k, cu, cv);
    float3 w = p0 + du * cu + dv * cv;
    uint kind = (q.y >> 29) & 0x7;
    float3 qa = p0 - n * scale, qb = p0 + du + dv;
    bool cull = kind == LOD_KIND_WATER || nearCoveredBox(min(qa, qb) + 0.01, max(qa, qb) - 0.01);
    float4 clip = mul(gFrame.viewProj, float4(w, 1.0));
    clip.xy = distortShadow(clip.xy);
    LodShadowVertOut o;
    o.pos = cull ? float4(0, 0, 0, 1) : clip;
    o.inner = w - n * min(0.5, scale * 0.5);
    return o;
}

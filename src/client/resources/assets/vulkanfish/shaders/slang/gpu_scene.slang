// gpu_scene.slang – geteilte GPU-Scene-Layouts (per #include eingebunden).
// Keine Entrypoints hier. Alle Hot-Path-Daten liegen in SSBOs, die CPU
// schreibt pro Frame nur Deltas (dirty Sections, geänderte Instanzen).
#ifndef VULKANFISH_GPU_SCENE_SLANG
#define VULKANFISH_GPU_SCENE_SLANG

// ---- Frame-Treiber (skalare Werte, Farbrampen rechnet die GPU selbst) ----
// std140, von NativePassRunner.writeUniforms() befuellt (Offsets dort gespiegelt).
struct FrameUniforms
{
    float4x4 viewProj;        //   0
    float4x4 prevViewProj;    //  64  Matrix, mit der die Hi-Z entstand
    float4x4 invViewProj;     // 128  Rekonstruktion Welt-Position aus Tiefe
    float4x4 shadowViewProj;  // 192  Ortho-Lichtraum (vor Verzerrung)
    float3 camPos;            // 256
    float time;               // 268  Sekunden (Wind, Sterne-Funkeln)
    float3 sunDir;            // 272  Richtung ZUR Sonne (Welt)
    float sunVisibility;      // 284  0 Nacht .. 1 Tag (weich ueber Daemmerung)
    float3 lightDir;          // 288  Richtung zum schattenwerfenden Licht (Sonne bzw. Mond)
    float noonFactor;         // 300  0 Horizont .. 1 Mittag
    float3 skyColor;          // 304  Vanillas Himmelsfarbe (Biom/Wetter) als Treiber
    float renderDistance;     // 316  Bloecke
    float3 fogColor;          // 320  Vanillas Fog-Farbe (Unterwasser/Lava/Pulverschnee)
    float rainFactor;         // 332
    float2 screenSize;        // 336
    uint hizMipCount;         // 344  0 = Hi-Z aus
    uint frameIndex;          // 348
    float shadowDistance;     // 352  Halbe Kantenlaenge des Lichtraums (Bloecke), 0 = keine Schatten
    uint dimension;           // 356  0 Overworld, 1 Nether, 2 End
    uint fogType;             // 360  0 Luft, 1 Wasser, 2 Lava, 3 Pulverschnee
    float moonBrightness;     // 364  Mondphase 0..1
    float4 frustumPlanes[6];  // 368  xyz=Normal, w=Distanz (Welt-Raum)
    float lodErrorThreshold;  // 464  Screen-Space-Error in px
    float thunderFactor;      // 468
    float caveFactor;         // 472  0 offen .. 1 tief in Hoehlen (Kamera-Himmelslicht)
    float exposure;           // 476
    uint debugView;           // 480  0 aus, 1 Schatten, 2 Normalen, 3 Albedo, 4 Licht, 5 Wasser magenta, 10 LOD magenta
    uint cullFlags;           // 484  bit0: Wasser + Glas/Eis ueberspringen (Schatten-Cull)
    uint rtFlags;             // 488  bit0: Raytracing-Blocklicht aktiv
    uint rtLightCount;        // 492
    int4 lightGrid;           // 496  xyz = Ursprung des Lichtgitters (Zellen a 8 Bloecke)
    float fullbright;         // 512  0 aus .. 1 (Videoeinstellungen)
    float pad516, pad520, pad524; // einzeln: ein float3 hier landet je nach Packregel auf 516 oder 528
    // Alle Positionen (Matrizen, camPos, Lichter, TLAS) sind relativ zu diesem Render-Ursprung
    // (Welt, Bloecke, Vielfaches von 16): float bleibt auch bei Koordinaten in Millionenhoehe genau
    int4 renderOrigin;        // 528
    float4 worldWrap;         // 544  renderOrigin mod 4096: Weltlage fuer Rauschen (Wind, Wellen, Kaustik)
};                            // 560 Byte

// Ganzzahliger Welt-Ursprung (Section, LOD-Knoten) -> relativer Raum (exakt)
float3 relOrigin(FrameUniforms f, int x, int y, int z)
{
    return float3(int3(x, y, z) - f.renderOrigin.xyz);
}

// Relativer Punkt -> Welt-Chunk (x, z) bzw. Welt-Section-Y (fuer die Nahfeld-Abdeckung des LOD)
int2 worldChunk(FrameUniforms f, float2 relXZ)
{
    return int2(floor(relXZ / 16.0)) + (f.renderOrigin.xz >> 4);
}

int worldSectionY(FrameUniforms f, float relY)
{
    return int(floor(relY / 16.0)) + (f.renderOrigin.y >> 4);
}

// ---- Chunk-Cluster (LOD-Einheit, 2x2 Sections nah, groesser fern) ----
struct Cluster
{
    float3 center;       // Welt-Raum
    float lodError;      // geometrischer Fehler in Bloecken
    float3 extent;       // halbe AABB-Ausdehnung
    uint meshletStart;
    float3 coneAxis;     // Normal-Cone (Backface auf Clusterebene)
    uint meshletCount;
    float coneCutoff;    // cos(oeffnungswinkel), -1 = keine
    uint flags;          // bit0: translucent, bit1: fernfeld-LOD
};

// ---- Meshlet (64 Verts / 124 Tris max) ----
struct MeshletHeader
{
    uint vertexStart;
    uint vertexCount;
    uint indexStart;     // in uint-Indexbuffer
    uint triCount;
    uint clusterIdx;     // Eltern-Cluster; Bit 31 = Wasser, Bit 30 = Glas/Eis (Transluzenz-Pass)
    int originX;         // Section-Ursprung (Bloecke) fuer TerrainVertex-Positionen;
    int originY;         // belegt das std430-Padding vor boundCenter (Offsets 20/24/28)
    int originZ;
    float3 boundCenter;  // relativ zum Ursprung origin (nicht Welt)
    float boundRadius;
    // Backface-Test fuer achsenparallele Face-Gruppen: sichtbar gdw.
    // dot(camPos, coneAxis) >= coneCutoff (= min dot(p, n) aller Quads, p relativ zu origin).
    // coneAxis = 0 -> kein Test.
    float3 coneAxis;
    float coneCutoff;
};
// std430-Layout (Slang-Reflection): vertexStart 0, vertexCount 4, indexStart 8,
// triCount 12, clusterIdx 16, origin 20/24/28, boundCenter 32, boundRadius 44, coneAxis 48,
// coneCutoff 60 -> 64 Byte. Cluster: center 0, lodError 12, extent 16,
// meshletStart 28, coneAxis 32, meshletCount 44, coneCutoff 48, flags 52 -> 64.

struct PackedVertex
{
    float3 pos;      // Welt-Raum (statisch, kein CPU-Matrix-Update)
    float3 normal;
    float2 uv;
    float4 color;    // Blockfarbe / Tint
    float2 light;    // x=block, y=sky (0..1)
    float ao;
};

// ---- Kompakter Terrain-Vertex (16 Byte) ----
// Vom CPU-Mesher (Vanilla-ModelBlockRenderer -> TerrainStreamer) gepackt.
// Position section-relativ: u16 = (rel + 8) * 2048 (Bereich -8..24 Bloecke,
// 1/2048 Block Aufloesung), Section-Ursprung steht im Meshlet-Header.
// Keine Normale: der Mesh-Shader leitet sie aus dem Quad ab (der Mesher
// garantiert die Windung: cross(p1-p0, p2-p0) zeigt nach aussen).
struct TerrainVertex
{
    uint posXY;       // u16 x | u16 y << 16
    uint posZLight;   // u16 z | u8 Blocklicht << 16 | u8 Himmelslicht << 24 (0..240)
    uint uv;          // unorm16 u | unorm16 v << 16 (Block-Atlas)
    uint color;       // RGBA8: rgb = Tint*AO*Shade (Vanilla-QuadInstance), a = Emission
};

float3 tvPos(TerrainVertex v, float3 origin)
{
    float3 q = float3(float(v.posXY & 0xFFFF), float(v.posXY >> 16), float(v.posZLight & 0xFFFF));
    return origin + q / 2048.0 - 8.0;
}

float2 tvUv(TerrainVertex v)
{
    return float2(float(v.uv & 0xFFFF), float(v.uv >> 16)) / 65535.0;
}

float4 tvColor(TerrainVertex v)
{
    return float4(float(v.color & 0xFF), float((v.color >> 8) & 0xFF),
                  float((v.color >> 16) & 0xFF), float(v.color >> 24)) / 255.0;
}

float2 tvLight(TerrainVertex v)
{
    return float2(float((v.posZLight >> 16) & 0xFF), float(v.posZLight >> 24)) / 240.0;
}

// Terrain-Indices: 1 uint pro Dreieck, 3x 8 Bit lokale Vertex-Indices (0..63).
uint3 unpackTri(uint p)
{
    return uint3(p & 0xFF, (p >> 8) & 0xFF, (p >> 16) & 0xFF);
}

// ---- Entity-Instanz (30k+, 104 Byte) ----
struct EntityInstance
{
    float3x4 model;      // Rotation+Translation (CPU schreibt nur Deltas)
    float4 tint_flags;   // rgb=Tint, a=Flags (Fuse-Blitz, ...)
    float3 aabbMin;
    float emissive;
    float3 aabbMax;
    uint archetype;      // Geometrie-Typ (ein BLAS pro Archetyp im RT-Pfad)
    float2 light;        // x=Block, y=Himmel (0..1, wie G-Buffer-Light)
};

// ---- VkDrawMeshTasksIndirectCommandEXT ----
struct MeshTasksIndirect
{
    uint x;
    uint y;
    uint z;
};

// ---- Task -> Mesh Payload ----
static const uint MAX_PAYLOAD_MESHLETS = 32;

struct TerrainPayload
{
    uint meshletIndices[MAX_PAYLOAD_MESHLETS];
    uint count;
};

struct EntityPayload
{
    uint instanceIndices[32];
    uint count;
};

#endif

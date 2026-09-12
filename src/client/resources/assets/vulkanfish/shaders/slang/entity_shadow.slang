// entity_shadow.slang – Entities, Items und Block-Entities in die Schattenkarte (eigene
// Implementierung). Die Vertices kommen direkt aus Vanillas geteiltem Vertexpuffer des Frames
// (kamerarelativ, weltachsig); nur die Position wird gelesen. Gleiche radiale Verzerrung wie
// der Terrain-Schattenpass (look.slang: distortShadow).
#include "look.slang"

[[vk::binding(0, 0)]] ConstantBuffer<FrameUniforms> gFrame; // Kamera-Uniforms: echte Kamera + shadowViewProj

[shader("vertex")]
float4 entityShadowVert([[vk::location(0)]] float3 pos : POSITION) : SV_Position
{
    FrameUniforms f = gFrame;
    float4 clip = mul(f.shadowViewProj, float4(pos + f.camPos, 1.0));
    clip.xy = distortShadow(clip.xy);
    return clip;
}

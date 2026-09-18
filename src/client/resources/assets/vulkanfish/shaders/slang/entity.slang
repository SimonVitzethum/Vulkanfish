// entity.slang – Instanced starre Entities (TNT, fallende Bloecke): ein Draw pro Modell
// fuer alle Instanzen. Baked Quads (Modellraum 0..1, Atlas-UVs) + Instanz-Matrizen.
#include "look.slang"

struct EntityBakedVert
{
    float3 pos;
    float3 nrm;
    float2 uv;
};

[[vk::binding(0, 0)]] StructuredBuffer<EntityBakedVert> gEntityVerts;
[[vk::binding(1, 0)]] StructuredBuffer<EntityInstance> gEntityInst;
[[vk::binding(2, 0)]] ConstantBuffer<FrameUniforms> gFrame;
[[vk::binding(3, 0)]] Texture2D<float4> gAtlas;
[[vk::binding(4, 0)]] SamplerState gAtlasSamp;

struct EntityVertOut
{
    float4 pos : SV_Position;
    float2 uv;
    float3 normal;
    float2 light;
    float3 tint;
    float white;
};

EntityVertOut entityTransform(uint vid, uint iid)
{
    EntityBakedVert v = gEntityVerts[vid];
    EntityInstance t = gEntityInst[iid];
    float3 wp = mul(t.model, float4(v.pos, 1.0)).xyz;
    EntityVertOut o;
    o.pos = mul(gFrame.viewProj, float4(wp, 1.0));
    o.uv = v.uv;
    o.normal = normalize(mul((float3x3) t.model, v.nrm));
    o.light = t.light;
    o.tint = t.tint_flags.rgb;
    o.white = t.tint_flags.a;
    return o;
}

[shader("vertex")]
EntityVertOut entityVert(uint vid : SV_VertexID, uint iid : SV_InstanceID)
{
    return entityTransform(vid, iid);
}

// Schatten: gleiche Transformation in den verzerrten Lichtraum (Tiefe only, kein Frag).
[shader("vertex")]
float4 entityShadowInstVert(uint vid : SV_VertexID, uint iid : SV_InstanceID) : SV_Position
{
    EntityVertOut o = entityTransform(vid, iid);
    o.pos.xy = distortShadow(o.pos.xy);
    return o.pos;
}

struct EntityGBufferOut
{
    float4 albedoMat : SV_Target0;   // RGBA8: Albedo (Gamma) | Material (0 = solide)
    float4 normalLight : SV_Target1; // RGBA8: Oktaeder-Normale xy | Blocklicht | Himmelslicht
};

[shader("fragment")]
EntityGBufferOut entityFrag(EntityVertOut i)
{
    float4 tex = gAtlas.Sample(gAtlasSamp, i.uv);
    float3 alb = lerp(tex.rgb * i.tint, float3(1.0), saturate(i.white));
    EntityGBufferOut o;
    o.albedoMat = float4(alb, 0.0);
    o.normalLight = float4(octEncode(normalize(i.normal)), saturate(i.light));
    return o;
}

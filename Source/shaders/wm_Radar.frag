#version 310 es
// WatchMaker Radar (ported from UL Radar.fsh, math verbatim).
// Polar sweep with a 100° fade behind the leading edge: u_1 edge angle,
// u_2 opacity at the edge %, u_3 opacity outside/tail %.
precision highp float;
precision highp int;

layout(location = TEXCOORD0) in vec2 v_texCoord;
layout(location = COLOR0) in vec4 v_color;
layout(binding = 0) uniform sampler2D u_tex0;

layout(std140) uniform fs_ub {
    float u_1;
    float u_2;
    float u_3;
};

layout(location = SV_Target0) out vec4 FragColor;

#define M_PI 3.1415926535897932384626433832795

bool isBetween(float number_, float min_, float max_)
{
    return number_ >= min_ && number_ <= max_;
}

void main()
{
    vec4 color = texture(u_tex0, v_texCoord) * v_color;
    float angleCover = 100.0;
    vec2 centerPos = vec2(0.5, 0.5);
    float angle = atan(v_texCoord.x - centerPos.x, centerPos.y - v_texCoord.y) * 180.0 / M_PI;
    if (angle < u_1 - angleCover)
    {
        angle += 360.0;
    }
    if (isBetween(angle, u_1 - angleCover, u_1))
    {
        float distTop = u_1 - angle;
        float pctUniform2 = distTop / angleCover;
        float pctUniform3 = 1.0 - pctUniform2;
        float mixOpacity = pctUniform2 * u_3 + pctUniform3 * u_2;
        FragColor = color * (mixOpacity / 100.0);   // premultiplied: fade rgb with alpha
    }
    else
    {
        FragColor = color * (u_3 / 100.0);   // premultiplied: fade rgb with alpha
    }
}

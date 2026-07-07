#version 310 es
// WatchMaker SegmentBetween (ported from UL SegmentBetween.fsh, math verbatim).
// Fills between angles u_1..u_2 (wraps through 12 o'clock when u_2 < u_1):
// u_3 opacity inside %, u_4 opacity outside %.
precision highp float;
precision highp int;

layout(location = TEXCOORD0) in vec2 v_texCoord;
layout(location = COLOR0) in vec4 v_color;
layout(binding = 0) uniform sampler2D u_tex0;

layout(std140) uniform fs_ub {
    float u_1;
    float u_2;
    float u_3;
    float u_4;
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
    vec2 centerPos = vec2(0.5, 0.5);
    float angle = atan(v_texCoord.x - centerPos.x, centerPos.y - v_texCoord.y) * 180.0 / M_PI;
    if (angle < 0.0 && u_1 > 0.0)
    {
        angle += 360.0;
    }
    if (isBetween(angle, u_1, u_2) ||
        (u_2 < u_1 && isBetween(angle, u_1, 360.0)) ||
        (u_2 < u_1 && isBetween(angle, 0.0, u_2)))
    {
        FragColor = vec4(color.rgb, color.a * u_3 / 100.0);
    }
    else
    {
        FragColor = vec4(color.rgb, color.a * u_4 / 100.0);
    }
}

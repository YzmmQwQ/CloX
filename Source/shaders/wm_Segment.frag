#version 310 es
// WatchMaker Segment (ported from UL Segment.fsh, math verbatim).
// Circular wedge clockwise from 12 o'clock: u_1 span degrees,
// u_2 opacity inside %, u_3 opacity outside %.
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

void main()
{
    vec4 color = texture(u_tex0, v_texCoord) * v_color;
    vec2 centerPos = vec2(0.5, 0.5);
    float angle = atan(v_texCoord.x - centerPos.x, centerPos.y - v_texCoord.y) * 180.0 / M_PI;
    if (angle < 0.0)
    {
        angle += 360.0;
    }
    if (angle > 0.0 && angle <= u_1)
    {
        FragColor = color * (u_2 / 100.0);   // premultiplied: fade rgb with alpha
    }
    else
    {
        FragColor = color * (u_3 / 100.0);   // premultiplied: fade rgb with alpha
    }
}

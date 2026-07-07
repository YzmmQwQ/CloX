#version 310 es
// WatchMaker Progress (ported from UL Progress.fsh, math verbatim).
// Directional fill: u_1 fill %, u_2 direction angle degrees,
// u_3 opacity filled %, u_4 opacity unfilled %.
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

#define PI 3.1415926535897932384626433832795

void main()
{
    vec4 color = texture(u_tex0, v_texCoord) * v_color;
    float yNew = 0.5 + (v_texCoord.x - 0.5) * sin(-u_2 * PI / 180.0) + (v_texCoord.y - 0.5) * cos(-u_2 * PI / 180.0);
    yNew = 1.0 - clamp(yNew, 0.0, 1.0);
    if (yNew <= u_1 / 100.0)
    {
        FragColor = color * (u_3 / 100.0);   // premultiplied: fade rgb with alpha
    }
    else
    {
        FragColor = color * (u_4 / 100.0);   // premultiplied: fade rgb with alpha
    }
}

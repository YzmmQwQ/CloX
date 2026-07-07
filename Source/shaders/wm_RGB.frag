#version 310 es
// WatchMaker RGB (ported from UL RGB.fsh, math verbatim).
// u_1/u_2/u_3 red/green/blue channel shift %.
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

void main()
{
    vec4 color = texture(u_tex0, v_texCoord) * v_color;
    color.r = clamp(color.r + u_1 / 100.0, 0.0, 1.0);
    color.g = clamp(color.g + u_2 / 100.0, 0.0, 1.0);
    color.b = clamp(color.b + u_3 / 100.0, 0.0, 1.0);
    FragColor = color;
}

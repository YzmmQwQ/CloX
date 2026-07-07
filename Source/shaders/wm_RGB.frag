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
    // premultiplied: shift scales with alpha so transparent texels stay
    // transparent (straight-alpha clamp(c+s,0,1) re-premultiplied by a)
    color.rgb = clamp(color.rgb + vec3(u_1, u_2, u_3) / 100.0 * color.a, 0.0, color.a);
    FragColor = color;
}

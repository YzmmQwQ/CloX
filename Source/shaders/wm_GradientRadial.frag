#version 310 es
// WatchMaker GradientRadial (ported from UL GradientRadial.fsh, math verbatim).
// u_1 centre color, u_2 edge color, u_3 radius scale %.
precision highp float;
precision highp int;

layout(location = TEXCOORD0) in vec2 v_texCoord;
layout(location = COLOR0) in vec4 v_color;
layout(binding = 0) uniform sampler2D u_tex0;

layout(std140) uniform fs_ub {
    vec3 u_1;
    vec3 u_2;
    float u_3;
};

layout(location = SV_Target0) out vec4 FragColor;

void main()
{
    vec4 color = texture(u_tex0, v_texCoord) * v_color;
    float yNew = length(v_texCoord - vec2(0.5, 0.5));
    yNew /= (u_3 / 100.0);
    yNew = clamp(yNew, 0.0, 1.0);
    vec3 mixed = vec3(mix(u_1, u_2, yNew));
    FragColor = vec4(mixed * color.a, color.a);   // premultiplied — transparent texels stay transparent
}

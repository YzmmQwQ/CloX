#version 310 es
// WatchMaker GradientLinear (ported from UL GradientLinear.fsh, math verbatim).
// u_1/u_2 start/end color, u_3 angle degrees, u_4 scale %.
// UL's u_opacity arrives premixed in v_color.a.
precision highp float;
precision highp int;

layout(location = TEXCOORD0) in vec2 v_texCoord;
layout(location = COLOR0) in vec4 v_color;
layout(binding = 0) uniform sampler2D u_tex0;

layout(std140) uniform fs_ub {
    vec3 u_1;
    vec3 u_2;
    float u_3;
    float u_4;
};

layout(location = SV_Target0) out vec4 FragColor;

#define PI 3.1415926535897932384626433832795

void main()
{
    vec4 color = texture(u_tex0, v_texCoord) * v_color;
    float yNew = 0.5 + (v_texCoord.x - 0.5) * sin(-u_3 * PI / 180.0) + (v_texCoord.y - 0.5) * cos(-u_3 * PI / 180.0);
    yNew = (yNew - 0.5) / (u_4 / 100.0) + 0.5;
    yNew = clamp(yNew, 0.0, 1.0);
    vec3 mixed = vec3(mix(u_1, u_2, yNew));
    FragColor = vec4(mixed, color.a);
}

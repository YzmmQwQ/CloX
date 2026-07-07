#version 310 es
// WatchMaker Rainbow (ported from UL Rainbow.fsh, math verbatim —
// R tracks sin(u_1), G/B track the texcoords).
precision highp float;
precision highp int;

layout(location = TEXCOORD0) in vec2 v_texCoord;
layout(location = COLOR0) in vec4 v_color;
layout(binding = 0) uniform sampler2D u_tex0;

layout(std140) uniform fs_ub {
    float u_1;
};

layout(location = SV_Target0) out vec4 FragColor;

void main()
{
    vec4 color1 = texture(u_tex0, v_texCoord);
    vec3 color2 = vec3((sin(u_1) + 1.0) / 2.0, v_texCoord.x, v_texCoord.y);
    float a = color1.a * v_color.a;
    FragColor = vec4(color2 * a, a);   // premultiplied
}

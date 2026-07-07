#version 310 es
// WatchMaker ProgressBetween (ported from UL ProgressBetween.fsh, math
// verbatim — UL's v_screenX/v_screenY varyings come from its custom vertex
// shader and equal the quad's normalized coords, i.e. our v_texCoord).
// u_1 from %, u_2 to %, u_3 direction angle degrees,
// u_4 opacity filled %, u_5 opacity unfilled %.
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
    float u_5;
};

layout(location = SV_Target0) out vec4 FragColor;

#define PI 3.1415926535897932384626433832795

bool isBetween(float number_, float min_, float max_)
{
    return number_ >= min_ && number_ <= max_;
}

void main()
{
    vec4 color = texture(u_tex0, v_texCoord) * v_color;
    float yNew = 0.5 + (v_texCoord.x - 0.5) * sin(-u_3 * PI / 180.0) + (v_texCoord.y - 0.5) * cos(-u_3 * PI / 180.0);
    yNew = clamp(yNew, 0.0, 1.0);
    if (isBetween(yNew, u_1 / 100.0, u_2 / 100.0))
    {
        FragColor = vec4(color.rgb, color.a * u_4 / 100.0);
    }
    else
    {
        FragColor = vec4(color.rgb, color.a * u_5 / 100.0);
    }
}

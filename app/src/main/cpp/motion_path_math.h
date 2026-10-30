#pragma once
#include <cmath>

namespace am {
struct PathSample { float x, y, dx, dy; };

// Controls are relative to their key. An untouched segment retains linear
// spatial interpolation, including its original temporal easing.
inline PathSample SampleMotionPath(float ax, float ay, float bx, float by,
                                  float ox, float oy, float ix, float iy, float u) {
    if (ox == 0 && oy == 0 && ix == 0 && iy == 0)
        return {ax + (bx - ax) * u, ay + (by - ay) * u, bx - ax, by - ay};
    const float s = 1 - u, cx = ax + ox, cy = ay + oy, ex = bx + ix, ey = by + iy;
    return {s*s*s*ax + 3*s*s*u*cx + 3*s*u*u*ex + u*u*u*bx,
            s*s*s*ay + 3*s*s*u*cy + 3*s*u*u*ey + u*u*u*by,
            3*s*s*ox + 6*s*u*(ex-cx) - 3*u*u*ix,
            3*s*s*oy + 6*s*u*(ey-cy) - 3*u*u*iy};
}

inline float MotionPathHeading(float ax, float ay, float bx, float by,
                               float ox, float oy, float ix, float iy, float u) {
    PathSample p = SampleMotionPath(ax,ay,bx,by,ox,oy,ix,iy,u);
    if (std::hypot(p.dx,p.dy) < 1e-5f) {
        // Zero-length endpoint controls are common. Use the one-sided tangent
        // rather than snapping a stationary endpoint back to zero degrees.
        const float near = u >= 0.5f ? u - 0.001f : u + 0.001f;
        p = SampleMotionPath(ax,ay,bx,by,ox,oy,ix,iy,near);
    }
    return std::hypot(p.dx,p.dy) < 1e-5f ? 0 : std::atan2(p.dy,p.dx) * 57.2957795131f;
}
}

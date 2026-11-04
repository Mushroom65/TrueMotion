#pragma once

// A bounded, order-independent four-fragment depth buffer. No Android/Skia dependency.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

namespace am::depth {
struct Color { float r=0, g=0, b=0, a=0; }; // straight RGBA
inline uint32_t pack(Color c) {
    auto q=[](float x) { return (uint32_t)std::lround(std::clamp(x,0.f,1.f)*255); };
    return q(c.r)|(q(c.g)<<8)|(q(c.b)<<16)|(q(c.a)<<24);
}
inline Color unpack(uint32_t c) { return {(c&255)/255.f,((c>>8)&255)/255.f,((c>>16)&255)/255.f,(c>>24)/255.f}; }
struct Fragment { float z=std::numeric_limits<float>::infinity(); uint32_t color=0; };
struct Pixel { std::array<Fragment,4> f; };
class Buffer {
public:
    int width, height;
    std::vector<Pixel> pixels;
    Buffer(int w,int h):width(w),height(h),pixels((size_t)w*h) {}
    void add(int x,int y,float z,Color c,bool additive=false) {
        if (!std::isfinite(z)||z<1||c.a<1.f/255||x<0||x>=width||y<0||y>=height) return;
        auto& p=pixels[(size_t)y*width+x].f;
        for (int k=0;k<4;k++) {
            if (std::fabs(p[k].z)>z+1e-4f) break;
            if ((p[k].color>>24)==255 && p[k].z>0 && p[k].z<z-1e-4f) return;
        }
        int at=0;
        while(at<4 && std::fabs(p[at].z)<z-1e-4f) at++;
        if(at==4) return;
        for(int k=3;k>at;k--) p[k]=p[k-1];
        p[at]={additive?-z:z,pack(c)};
        if(c.a>=1 && !additive) for(int k=at+1;k<4;k++) p[k]=Fragment{};
    }
    std::vector<uint8_t> resolve() const {
        std::vector<uint8_t> out((size_t)width*height*4);
        for(size_t i=0;i<pixels.size();i++) {
            Color dst{}; // premultiplied during composition
            for(int k=3;k>=0;k--) {
                const auto& f=pixels[i].f[k]; const Color s=unpack(f.color);
                const float behind=f.z<0?1:1-s.a;
                dst={std::min(1.f,s.r*s.a+dst.r*behind),std::min(1.f,s.g*s.a+dst.g*behind),std::min(1.f,s.b*s.a+dst.b*behind),std::min(1.f,s.a+dst.a*behind)};
            }
            out[i*4]=(uint8_t)std::lround(dst.r*255); out[i*4+1]=(uint8_t)std::lround(dst.g*255);
            out[i*4+2]=(uint8_t)std::lround(dst.b*255); out[i*4+3]=(uint8_t)std::lround(dst.a*255);
        }
        return out;
    }
};
struct Texture {
    int width=0,height=0; std::vector<uint8_t> rgba; // straight RGBA
    Color sample(float u,float v,bool repeat=false) const {
        if(width<1||height<1||rgba.size()!=(size_t)width*height*4) return {1,1,1,1};
        if(repeat) {u-=std::floor(u);v-=std::floor(v);} else {u=std::clamp(u,0.f,1.f);v=std::clamp(v,0.f,1.f);}
        const float x=u*width-.5f,y=v*height-.5f;
        const int x0=(int)std::floor(x),y0=(int)std::floor(y);
        const float a=x-x0,b=y-y0;
        Color out{};
        for(int j=0;j<2;j++) for(int i=0;i<2;i++) {
            auto index=[repeat](int k,int n) {return repeat?(k%n+n)%n:std::clamp(k,0,n-1);};
            const size_t k=((size_t)index(y0+j,height)*width+index(x0+i,width))*4;
            const float w=(i?a:1-a)*(j?b:1-b)/255;
            // Filter premultiplied colors to avoid dark fringes around cutouts.
            const float alpha=rgba[k+3]/255.f;
            out.r+=rgba[k]*alpha*w;out.g+=rgba[k+1]*alpha*w;out.b+=rgba[k+2]*alpha*w;out.a+=rgba[k+3]*w;
        }
        if(out.a>0) {out.r/=out.a;out.g/=out.a;out.b/=out.a;}
        return out;
    }
};
struct Vertex { float x,y,z,w,u=0,v=0; Color color{1,1,1,1}; };
inline Vertex mix(const Vertex& a,const Vertex& b,float t) {
    auto f=[t](float x,float y){return x+(y-x)*t;};
    return {f(a.x,b.x),f(a.y,b.y),f(a.z,b.z),f(a.w,b.w),f(a.u,b.u),f(a.v,b.v),
        {f(a.color.r,b.color.r),f(a.color.g,b.color.g),f(a.color.b,b.color.b),f(a.color.a,b.color.a)}};
}
inline void triangle(Buffer& buffer,Vertex a,Vertex b,Vertex c,const Texture* texture=nullptr,bool additive=false,bool repeat=true) {
    // Clip in homogeneous camera space before projection (also works for orthographic cameras).
    std::vector<Vertex> polygon{a,b,c};
    for(int plane=0;plane<2;plane++) {
        std::vector<Vertex> next;
        auto side=[plane](const Vertex& v){return plane==0?v.z-1:v.w-1e-5f;};
        for(size_t i=0;i<polygon.size();i++) {
            const auto& p=polygon[i];const auto& q=polygon[(i+1)%polygon.size()];
            const float sp=side(p),sq=side(q);
            if(sp>=0) next.push_back(p);
            if((sp>=0)!=(sq>=0)) next.push_back(mix(p,q,sp/(sp-sq)));
        }
        polygon=std::move(next);
    }
    for(size_t fan=1;fan+1<polygon.size();fan++) {
        std::array<Vertex,3> v{polygon[0],polygon[fan],polygon[fan+1]};
        for(auto& p:v) {p.x/=p.w;p.y/=p.w;}
        auto edge=[](const Vertex& p,const Vertex& q,float x,float y){return (q.x-p.x)*(y-p.y)-(q.y-p.y)*(x-p.x);};
        float area=edge(v[0],v[1],v[2].x,v[2].y);
        if(!std::isfinite(area)||std::fabs(area)<1e-6) continue;
        if(area<0) {std::swap(v[1],v[2]);area=-area;}
        const float minX=std::min({v[0].x,v[1].x,v[2].x}),maxX=std::max({v[0].x,v[1].x,v[2].x});
        const float minY=std::min({v[0].y,v[1].y,v[2].y}),maxY=std::max({v[0].y,v[1].y,v[2].y});
        if(!std::isfinite(minX)||!std::isfinite(maxX)||!std::isfinite(minY)||!std::isfinite(maxY)) continue;
        const int left=(int)std::clamp(std::floor(minX),0.f,(float)buffer.width),right=(int)std::clamp(std::ceil(maxX),0.f,(float)buffer.width);
        const int top=(int)std::clamp(std::floor(minY),0.f,(float)buffer.height),bottom=(int)std::clamp(std::ceil(maxY),0.f,(float)buffer.height);
        auto inside=[](float e,const Vertex& p,const Vertex& q){return e>0||(e==0&&(q.y<p.y||(q.y==p.y&&q.x>p.x)));};
        for(int y=top;y<bottom;y++) for(int x=left;x<right;x++) {
            const float px=x+.5f,py=y+.5f;
            const float e0=edge(v[1],v[2],px,py),e1=edge(v[2],v[0],px,py),e2=edge(v[0],v[1],px,py);
            if(!inside(e0,v[1],v[2])||!inside(e1,v[2],v[0])||!inside(e2,v[0],v[1])) continue;
            const float a=e0/area/v[0].w,b=e1/area/v[1].w,c=e2/area/v[2].w,r=1/(a+b+c);
            auto interp=[&](float p,float q,float s){return (a*p+b*q+c*s)*r;};
            Color color{interp(v[0].color.r,v[1].color.r,v[2].color.r),interp(v[0].color.g,v[1].color.g,v[2].color.g),interp(v[0].color.b,v[1].color.b,v[2].color.b),interp(v[0].color.a,v[1].color.a,v[2].color.a)};
            if(texture) {
                const Color tex=texture->sample(interp(v[0].u,v[1].u,v[2].u),interp(v[0].v,v[1].v,v[2].v),repeat);
                color={color.r*tex.r,color.g*tex.g,color.b*tex.b,color.a*tex.a};
            }
            buffer.add(x,y,interp(v[0].z,v[1].z,v[2].z),color,additive);
        }
    }
}
}

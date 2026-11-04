#include "depth_buffer.h"
#include <cassert>
#include <iostream>

using namespace am::depth;
static Vertex vertex(float x,float y,float z,Color color) {return {x,y,z,1,0,0,color};}
static void same(const std::vector<uint8_t>& a,const std::vector<uint8_t>& b) {assert(a==b);}
int main() {
    const Color red{1,0,0,1},blue{0,0,1,1},green{0,1,0,1};
    // Intersecting triangles have different front faces across the same image.
    auto crossing=[&](bool reverse) {
        Buffer b(40,40);
        auto a=[&]{triangle(b,vertex(0,0,2,red),vertex(40,0,10,red),vertex(0,40,2,red));};
        auto c=[&]{triangle(b,vertex(0,0,8,blue),vertex(40,0,2,blue),vertex(0,40,8,blue));};
        if(reverse){c();a();}else{a();c();}
        return b.resolve();
    };
    const auto cross=crossing(false);same(cross,crossing(true));
    assert(cross[(5*40+5)*4]==255);assert(cross[(5*40+30)*4+2]==255);
    // Coplanar painter order remains stable for normal 2D stacks.
    Buffer ties(1,1);ties.add(0,0,5,red);ties.add(0,0,5,blue);assert(ties.resolve()[2]==255);
    // Semitransparent surfaces and additive particles must be independent of submission order.
    for(bool additive:{false,true}) {
        Buffer a(1,1),b(1,1);Color tint=green;tint.a=.5f;
        a.add(0,0,10,red);a.add(0,0,5,tint,additive);
        b.add(0,0,5,tint,additive);b.add(0,0,10,red);same(a.resolve(),b.resolve());
        assert(a.resolve()[1]>=127);assert(a.resolve()[0]>0);
    }
    // Transparent texels do not hide a layer or model behind a billboard.
    Texture hole{1,1,{255,255,255,0}};Buffer holes(4,4);
    for(int y=0;y<4;y++)for(int x=0;x<4;x++)holes.add(x,y,10,blue);
    triangle(holes,vertex(0,0,2,red),vertex(4,0,2,red),vertex(0,4,2,red),&hole);
    assert(holes.resolve()[2]==255);
    // A transparent quad has no extra opacity along its shared triangle edge.
    Buffer quad(8,8);Color half=red;half.a=.5f;
    triangle(quad,vertex(0,0,3,half),vertex(8,0,3,half),vertex(8,8,3,half));
    triangle(quad,vertex(0,0,3,half),vertex(8,8,3,half),vertex(0,8,3,half));
    const auto q=quad.resolve();for(int i=0;i<64;i++)assert(q[i*4+3]==128);
    // Near-plane clipping retains the visible portion; invalid depth is ignored.
    Buffer near(8,8);triangle(near,vertex(0,0,.5,red),vertex(8,0,4,red),vertex(0,8,4,red));
    assert(near.resolve()[(2*8+2)*4]>0);
    near.add(2,2,std::numeric_limits<float>::quiet_NaN(),blue);assert(near.resolve()[(2*8+2)*4]>0);
    // Perspective-correct interpolation: a vertex at twice the homogeneous W contributes half as much.
    Buffer perspective(8,8);Vertex a{0,0,2,1,0,0,red},b{16,0,8,2,0,0,blue},c{0,8,2,1,0,0,red};
    triangle(perspective,a,b,c);const auto p=perspective.resolve();assert(p[(1*8+3)*4]>p[(1*8+3)*4+2]);
    std::cout<<"Shared depth: intersections, transparency, particles, cutouts, edges, clipping and perspective passed\n";
}

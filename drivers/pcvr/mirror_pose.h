#pragma once
#include "vendor/openvr.h"
#include <cmath>
inline vr::HmdMatrix34_t mirror_product(const vr::HmdMatrix34_t &a,const vr::HmdMatrix34_t &b) {
    vr::HmdMatrix34_t out{};
    for(int r=0;r<3;r++) {
        for(int c=0;c<3;c++) for(int k=0;k<3;k++) out.m[r][c]+=a.m[r][k]*b.m[k][c];
        out.m[r][3]=a.m[r][3];for(int k=0;k<3;k++) out.m[r][3]+=a.m[r][k]*b.m[k][3];
    }
    return out;
}
inline bool mirror_view(const vr::HmdMatrix34_t &pose,float left,float right,float down,float up,float out[11]) {
    for(const auto &row:pose.m) for(float v:row) if(!std::isfinite(v)) return false;
    for(int a=0;a<3;a++) for(int b=0;b<3;b++) {
        float dot=0;for(int k=0;k<3;k++) dot+=pose.m[k][a]*pose.m[k][b];
        if(std::fabs(dot-(a==b?1.f:0.f))>.005f) return false;
    }
    float det=pose.m[0][0]*(pose.m[1][1]*pose.m[2][2]-pose.m[1][2]*pose.m[2][1])
        -pose.m[0][1]*(pose.m[1][0]*pose.m[2][2]-pose.m[1][2]*pose.m[2][0])
        +pose.m[0][2]*(pose.m[1][0]*pose.m[2][1]-pose.m[1][1]*pose.m[2][0]);
    if(det<.99f || det>1.01f) return false;
    const float *r0=pose.m[0],*r1=pose.m[1],*r2=pose.m[2];
    float q[4]{},trace=r0[0]+r1[1]+r2[2];
    if(trace>0) {float s=2*std::sqrt(trace+1);q[3]=s/4;q[0]=(r2[1]-r1[2])/s;q[1]=(r0[2]-r2[0])/s;q[2]=(r1[0]-r0[1])/s;}
    else {
        int i=r1[1]>r0[0]?1:0;if(r2[2]>pose.m[i][i]) i=2;
        int j=(i+1)%3,k=(i+2)%3;float s=2*std::sqrt(1+pose.m[i][i]-pose.m[j][j]-pose.m[k][k]);
        if(s<1e-6f) return false;
        q[i]=s/4;q[j]=(pose.m[i][j]+pose.m[j][i])/s;q[k]=(pose.m[i][k]+pose.m[k][i])/s;q[3]=(pose.m[k][j]-pose.m[j][k])/s;
    }
    float norm=0;for(float v:q) norm+=v*v;
    if(!std::isfinite(norm) || norm<.98f || norm>1.02f) return false;
    for(int i=0;i<3;i++) out[i]=pose.m[i][3];
    for(int i=0;i<4;i++) out[i+3]=q[i]/std::sqrt(norm);
    out[7]=std::atan(left);out[8]=std::atan(right);out[9]=std::atan(up);out[10]=std::atan(down);
    for(int i=0;i<11;i++) if(!std::isfinite(out[i])) return false;
    return out[8]-out[7]>.05f && out[9]-out[10]>.05f;
}

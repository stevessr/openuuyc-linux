#pragma once
#include "pcvr_projection_probe.h"
#include <memory>
#include <mutex>
#include <chrono>
struct XrEnvironmentImage {int width=0,height=0;std::vector<uint8_t> rgba;};
struct XrEnvironmentSettings {
    int mode=0;uint32_t color=0xff000000;float opacity=0,brightness=.75f;bool flow=true;int seed=1;
    std::shared_ptr<XrEnvironmentImage> image;
};
class XrEnvironmentRenderer {
    PcvrProjectionProbe targets;
    GLuint program=0,vao=0,vbo=0,texture=0;
    std::shared_ptr<XrEnvironmentImage> uploaded;
    XrEnvironmentSettings settings;
    float time=0;
    bool failed=false;
    static GLuint compile(GLenum type,const char* source) {
        GLuint shader=glCreateShader(type);glShaderSource(shader,1,&source,nullptr);glCompileShader(shader);
        GLint ok=0;glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
        if(!ok) {glDeleteShader(shader);return 0;}return shader;
    }
public:
    static const char* fragment() {return R"glsl(#version 300 es
precision highp float;
in vec2 uv;
out vec4 out_color;
uniform vec4 orientation,frustum;
uniform vec3 eye,base_color;
uniform float clock_time,opacity,brightness,seed;
uniform int mode,has_image;
uniform sampler2D panorama;
float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7))+seed)*43758.5453);}
float noise(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),mix(hash(i+vec2(0,1)),hash(i+vec2(1)),f.x),f.y);}
float cloud(vec2 p){return noise(p)*.6+noise(p*2.03)*.3+noise(p*4.07)*.1;}
void main(){
 vec3 r=normalize(vec3(mix(frustum.x,frustum.y,uv.x),mix(frustum.z,frustum.w,uv.y),-1));
 r=r+2.*cross(orientation.xyz,cross(orientation.xyz,r)+orientation.w*r);
 float az=atan(r.x,-r.z),elev=asin(clamp(r.y,-1.,1.));
 vec2 st=vec2(az/6.2831853+.5,.5-elev/3.14159265);
 vec3 c=base_color;
 float t=clock_time;
 if(mode==12 && has_image==1) c=texture(panorama,st).rgb;
 else if(mode>0){
  c=mix(vec3(.018,.028,.060),vec3(.12,.17,.26),pow(max(0.,1.-abs(r.y)),3.));
  if(mode==1) c=mix(vec3(.035,.04,.1),vec3(.38,.17,.22),exp(-abs(r.y)*4.));
  else if(mode==2){float band=.16+.10*sin(az*2.+t*.35)+.05*sin(az*5.-t*.2);float glow=exp(-abs(r.y-band)*22.)*(.4+.6*cloud(vec2(az*3.,r.y*12.-t)));c+=mix(vec3(.03,.6,.36),vec3(.4,.16,.7),sin(az)*.5+.5)*glow;}
  else if(mode==3 || mode==9){vec2 grid=st*vec2(850.,425.);float star=step(.998,hash(floor(grid)))*pow(max(0.,1.-length(fract(grid)-.5)*2.),4.);c*=.3;c+=star*(.6+.4*sin(t+hash(floor(grid))*20.));if(mode==9)c+=vec3(.22,.05,.34)*pow(cloud(st*10.+vec2(t*.03,0)),3.);}
  else if(mode==4){float ridge=.01+.07*sin(az*3.+seed)+.025*sin(az*13.);if(r.y<ridge)c=mix(vec3(.035,.06,.09),vec3(.13,.19,.26),smoothstep(-.25,.10,r.y));}
  else if(mode==6){float sun=pow(max(0.,dot(r,normalize(vec3(.3,.12,-1.)))),700.);c=mix(vec3(.09,.08,.18),vec3(.58,.24,.15),exp(-abs(r.y-.03)*6.))+sun*vec3(.8,.55,.3);}
  else if(mode==7){float n=cloud(vec2(az*2.+t*.06,r.y*5.));c+=vec3(.18,.21,.26)*smoothstep(.45,.8,n)*smoothstep(-.1,.4,r.y);}
  else if(mode==8){if(r.y<0.){float d=(-1.5-eye.y)/r.y;vec2 p=(eye+r*d).xz;float wave=sin(p.x*.7+t)*sin(p.y*.6-t*.6);c=mix(vec3(.015,.09,.14),vec3(.06,.2,.27),wave*.5+.5);}else c+=vec3(.1,.15,.16)*cloud(st*12.+t*.04);}
  if(mode==5 || mode==11){if(r.y<-.001){float d=(-1.5-eye.y)/r.y;if(d>0.){vec2 p=(eye+r*d).xz;vec2 line=abs(fract(p)-.5);float grid=1.-smoothstep(.012,.035,min(line.x,line.y));c=mix(vec3(.015,.025,.035),mode==11?vec3(.25,.08,.4):vec3(.04,.28,.32),grid*exp(-d*.025));}}}
  if(mode==10){vec3 a=(vec3(-6.,-1.5,-6.)-eye)/r,b=(vec3(6.,4.,6.)-eye)/r;vec3 far_t=max(a,b);float d=min(far_t.x,min(far_t.y,far_t.z));if(d>0.){vec3 p=eye+r*d;if(p.y< -1.49){vec2 g=abs(fract(p.xz*.5)-.5);float line=1.-smoothstep(.006,.018,min(g.x,g.y));c=mix(vec3(.12,.14,.16),vec3(.075,.09,.11),line);}else c=vec3(.23,.26,.29)*(1.-.025*d)+vec3(.05,.04,.025)*pow(max(0.,r.y),2.);}}
 }
 c=clamp(c*brightness,0.,1.);
 out_color=vec4(c*opacity,opacity);
})glsl";}
    bool initialize(XrInstance instance,XrSystemId system,XrSession session) {
        if(program) return true;if(failed) return false;
        failed=true;
        const char* vertex="#version 300 es\nlayout(location=0) in vec2 position;out vec2 uv;void main(){uv=position*.5+.5;gl_Position=vec4(position,0,1);}";
        auto vs=compile(GL_VERTEX_SHADER,vertex),fs=compile(GL_FRAGMENT_SHADER,fragment());
        if(!vs || !fs) {if(vs)glDeleteShader(vs);if(fs)glDeleteShader(fs);return false;}
        GLuint candidate=glCreateProgram();glAttachShader(candidate,vs);glAttachShader(candidate,fs);glLinkProgram(candidate);glDeleteShader(vs);glDeleteShader(fs);
        GLint ok=0;glGetProgramiv(candidate,GL_LINK_STATUS,&ok);if(!ok){glDeleteProgram(candidate);return false;}
        if(!targets.initialize(instance,system,session,768)){glDeleteProgram(candidate);targets.destroy();return false;}
        program=candidate;
        const float vertices[]={-1,-1,3,-1,-1,3};
        glGenVertexArrays(1,&vao);glBindVertexArray(vao);glGenBuffers(1,&vbo);glBindBuffer(GL_ARRAY_BUFFER,vbo);
        glBufferData(GL_ARRAY_BUFFER,sizeof(vertices),vertices,GL_STATIC_DRAW);glEnableVertexAttribArray(0);glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,nullptr);
        glBindVertexArray(0);glBindBuffer(GL_ARRAY_BUFFER,0);
        glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_REPEAT);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        const uint8_t pixel[]={0,0,0,255};glTexImage2D(GL_TEXTURE_2D,0,GL_SRGB8_ALPHA8,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
        targets.layer.layerFlags=XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
        failed=false;return true;
    }
    const XrCompositionLayerBaseHeader* render(XrSpace world,XrTime display_time,const XrEnvironmentSettings& config) {
        if(!program || config.opacity<=0) return nullptr;
        settings=config;
        time=config.flow?float(std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count()-start_time):0.f;
        if(config.image && config.image!=uploaded) {
            glBindTexture(GL_TEXTURE_2D,texture);glTexImage2D(GL_TEXTURE_2D,0,GL_SRGB8_ALPHA8,config.image->width,config.image->height,0,GL_RGBA,GL_UNSIGNED_BYTE,config.image->rgba.data());uploaded=config.image;
        }
        auto result=targets.render(world,display_time,this,paint);
        return result==PcvrProjectionProbe::Result::Ready?reinterpret_cast<const XrCompositionLayerBaseHeader*>(&targets.layer):nullptr;
    }
    void destroy() {
        targets.destroy();if(program)glDeleteProgram(program);if(vbo)glDeleteBuffers(1,&vbo);if(vao)glDeleteVertexArrays(1,&vao);if(texture)glDeleteTextures(1,&texture);
        program=vao=vbo=texture=0;uploaded.reset();
    }
private:
    const double start_time=std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
    static bool paint(void* context,const XrView& view,uint32_t width,uint32_t height) {
        auto& self=*static_cast<XrEnvironmentRenderer*>(context);auto p=self.program;const auto& s=self.settings;
        glViewport(0,0,width,height);glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND);glDisable(GL_SCISSOR_TEST);glUseProgram(p);
        auto location=[&](const char* key){return glGetUniformLocation(p,key);};
        const auto& q=view.pose.orientation;const auto& e=view.pose.position;const auto& f=view.fov;
        glUniform4f(location("orientation"),q.x,q.y,q.z,q.w);
        glUniform4f(location("frustum"),std::tan(f.angleLeft),std::tan(f.angleRight),std::tan(f.angleDown),std::tan(f.angleUp));
        glUniform3f(location("eye"),e.x,e.y,e.z);glUniform3f(location("base_color"),((s.color>>16)&255)/255.f,((s.color>>8)&255)/255.f,(s.color&255)/255.f);
        glUniform1f(location("clock_time"),self.time);glUniform1f(location("opacity"),s.opacity);glUniform1f(location("brightness"),s.brightness);glUniform1f(location("seed"),float(s.seed&1023));
        glUniform1i(location("mode"),s.mode);glUniform1i(location("has_image"),s.image?1:0);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,self.texture);glUniform1i(location("panorama"),0);
        glBindVertexArray(self.vao);glDrawArrays(GL_TRIANGLES,0,3);glBindVertexArray(0);
        return glGetError()==GL_NO_ERROR;
    }
};

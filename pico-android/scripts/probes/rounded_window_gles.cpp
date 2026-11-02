// Host-only synthetic RGBA probe. Not a PICO compositor or video codec test.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <array>
#include <cassert>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>
GLuint shader(GLenum type,const char* source) {
    auto s=glCreateShader(type);glShaderSource(s,1,&source,nullptr);glCompileShader(s);
    GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
    if(!ok) {char log[4096]{};glGetShaderInfoLog(s,sizeof(log),nullptr,log);std::cerr<<log;}
    assert(ok);return s;
}
int main(int argc,char** argv) {
    if(argc!=2) {std::cerr<<"usage: rounded-window-gles compiled-fragment.frag\n";return 2;}
    std::ifstream file(argv[1]);assert(file);
    const std::string fragment((std::istreambuf_iterator<char>(file)),{});
    auto platform=reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
    auto display=platform?platform(EGL_PLATFORM_SURFACELESS_MESA,EGL_DEFAULT_DISPLAY,nullptr):eglGetDisplay(EGL_DEFAULT_DISPLAY);
    assert(eglInitialize(display,nullptr,nullptr));assert(eglBindAPI(EGL_OPENGL_ES_API));
    EGLint attributes[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLConfig config{};EGLint count=0;assert(eglChooseConfig(display,attributes,&config,1,&count) && count);
    EGLint context_attributes[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
    auto context=eglCreateContext(display,config,EGL_NO_CONTEXT,context_attributes);assert(context!=EGL_NO_CONTEXT);
    EGLint surface_attributes[]={EGL_WIDTH,256,EGL_HEIGHT,256,EGL_NONE};
    auto surface=eglCreatePbufferSurface(display,config,surface_attributes);assert(surface!=EGL_NO_SURFACE);
    assert(eglMakeCurrent(display,surface,surface,context));
    const char* vertex="attribute vec2 pos; varying vec2 tc; void main(){gl_Position=vec4(pos,0.,1.);tc=pos*.5+.5;}";
    auto vs=shader(GL_VERTEX_SHADER,vertex),fs=shader(GL_FRAGMENT_SHADER,fragment.c_str());
    auto program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);
    GLint ok=0;glGetProgramiv(program,GL_LINK_STATUS,&ok);assert(ok);glUseProgram(program);
    GLuint texture=0;glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    const unsigned char red[]={255,0,0,255};glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,red);
    glUniform1i(glGetUniformLocation(program,"s_tex"),0);
    const GLfloat vertices[]={-1,-1,1,-1,-1,1,1,1};auto position=glGetAttribLocation(program,"pos");
    glEnableVertexAttribArray(position);glVertexAttribPointer(position,2,GL_FLOAT,GL_FALSE,0,vertices);
    glViewport(0,0,256,256);glDisable(GL_DITHER);
    std::vector<unsigned char> pixels(256*256*4);
    for(auto size: {std::array<int,2>{1920,1080},{1080,1920},{256,256}}) {
        glUniform2f(glGetUniformLocation(program,"u_frame_size"),size[0],size[1]);
        glDrawArrays(GL_TRIANGLE_STRIP,0,4);glFinish();
        glReadPixels(0,0,256,256,GL_RGBA,GL_UNSIGNED_BYTE,pixels.data());assert(glGetError()==GL_NO_ERROR);
        auto alpha=[&](int x,int y){return pixels[(y*256+x)*4+3];};
        assert(alpha(128,128)==255 && alpha(0,128)==255);
        for(auto corner:{std::array<int,2>{0,0},{0,255},{255,0},{255,255}}) assert(alpha(corner[0],corner[1])<32);
    }
    glDeleteTextures(1,&texture);glDeleteProgram(program);glDeleteShader(vs);glDeleteShader(fs);
    eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroySurface(display,surface);eglDestroyContext(display,context);eglTerminate(display);
    std::cout<<"PASS: host GLES RGBA corner transparency; portrait/landscape/square, centre and straight edges preserved\n";
}

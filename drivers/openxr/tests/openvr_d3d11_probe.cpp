// Synthetic D3D11 stereo submissions through the real Windows OpenVR/Proton ABI.
// Requires openvr-2.5.1.h from the SHA-pinned xrizer source archive (IVRCompositor_028).
#include <windows.h>
#include <d3d11.h>
#include <dxgi1_2.h>
#include <openvr-2.5.1.h>
#include <cstdio>
#include <algorithm>
using Init=unsigned(__cdecl*)(int*,int,const char*);
using Shutdown=void(__cdecl*)();
using Interface=void*(__cdecl*)(const char*,int*);
int main(int argc,char**argv) {
 if(argc!=3)return 2;FILE *out=std::fopen(argv[2],"wb");if(!out)return 6;setvbuf(out,nullptr,_IONBF,0);
 HMODULE dll=LoadLibraryA(argv[1]);if(!dll){std::fprintf(out,"{\"dll_loaded\":false,\"error\":%lu}\n",GetLastError());return 3;}
 auto init=(Init)GetProcAddress(dll,"VR_InitInternal2");auto shutdown=(Shutdown)GetProcAddress(dll,"VR_ShutdownInternal");auto get=(Interface)GetProcAddress(dll,"VR_GetGenericInterface");if(!init||!shutdown||!get)return 4;
 int error=-1;init(&error,1,nullptr);std::fprintf(out,"{\"dll_loaded\":true,\"scene_init_error\":%d,\"game_started\":false}\n",error);if(error)return 5;
 const char *versions[]={"IVRChaperone_004","IVRCompositor_028","IVROverlayView_003","IVROverlay_027","IVRSystem_022"};
 bool missing=false;for(auto version:versions){int code=-1;void *iface=get(version,&code);bool valid=iface&&code==0;missing|=!valid;std::fprintf(out,"{\"interface\":\"%s\",\"available\":%s,\"error\":%d}\n",version,valid?"true":"false",code);}
 if(missing){shutdown();return 7;}
 auto system=(vr::IVRSystem*)get("IVRSystem_022",&error);auto compositor=(vr::IVRCompositor*)get("IVRCompositor_028",&error);
 IDXGIFactory1 *factory=nullptr;IDXGIAdapter1 *adapter=nullptr;ID3D11Device *device=nullptr;ID3D11DeviceContext *context=nullptr;ID3D11Texture2D *textures[2]{};ID3D11RenderTargetView *targets[2]{};
 unsigned submitted=0;int result=0;HRESULT hr=CreateDXGIFactory1(__uuidof(IDXGIFactory1),(void**)&factory);
 int index=-1;system->GetDXGIOutputInfo(&index);std::fprintf(out,"{\"recommended_dxgi_adapter\":%d}\n",index);
 if(FAILED(hr)||index<0||FAILED(hr=factory->EnumAdapters1(index,&adapter))){result=8;goto cleanup;}
 {D3D_FEATURE_LEVEL levels[]={D3D_FEATURE_LEVEL_11_0};hr=D3D11CreateDevice(adapter,D3D_DRIVER_TYPE_UNKNOWN,nullptr,D3D11_CREATE_DEVICE_BGRA_SUPPORT,levels,1,D3D11_SDK_VERSION,&device,nullptr,&context);}
 if(FAILED(hr)){result=9;goto cleanup;}
 {uint32_t width,height;system->GetRecommendedRenderTargetSize(&width,&height);width=std::min(width,1024u);height=std::min(height,1024u);
  for(int eye=0;eye<2;eye++) {D3D11_TEXTURE2D_DESC desc{};desc.Width=width;desc.Height=height;desc.MipLevels=1;desc.ArraySize=1;desc.Format=DXGI_FORMAT_R8G8B8A8_UNORM;desc.SampleDesc.Count=1;desc.Usage=D3D11_USAGE_DEFAULT;desc.BindFlags=D3D11_BIND_RENDER_TARGET|D3D11_BIND_SHADER_RESOURCE;
   hr=device->CreateTexture2D(&desc,nullptr,&textures[eye]);if(FAILED(hr)){result=10;goto cleanup;}hr=device->CreateRenderTargetView(textures[eye],nullptr,&targets[eye]);if(FAILED(hr)){result=11;goto cleanup;}
 }
 }
 compositor->SetTrackingSpace(vr::TrackingUniverseStanding);
 for(unsigned frame=0;frame<90;frame++) {
  vr::TrackedDevicePose_t poses[vr::k_unMaxTrackedDeviceCount]{};auto wait=compositor->WaitGetPoses(poses,vr::k_unMaxTrackedDeviceCount,nullptr,0);
  if(wait!=vr::VRCompositorError_None){std::fprintf(out,"{\"wait_error\":%d}\n",int(wait));result=12;break;}
  for(int eye=0;eye<2;eye++){float color[]={eye==0?1.f:0.f,eye==1?1.f:0.f,0,1};context->ClearRenderTargetView(targets[eye],color);}
  context->Flush();
  for(int eye=0;eye<2;eye++){vr::Texture_t texture{textures[eye],vr::TextureType_DirectX,vr::ColorSpace_Gamma};auto submit=compositor->Submit(vr::EVREye(eye),&texture);if(submit!=vr::VRCompositorError_None){std::fprintf(out,"{\"submit_error\":%d,\"eye\":%d}\n",int(submit),eye);result=13;break;}}
  if(result)break;submitted++;compositor->PostPresentHandoff();
 }
cleanup:
 std::fprintf(out,"{\"d3d11_stereo_frames_submitted\":%u,\"diagnostic_error\":%d,\"hresult\":%ld}\n",submitted,result,(long)hr);
 compositor->ClearLastSubmittedFrame();
 shutdown();std::fprintf(out,"{\"shutdown_complete\":true}\n");
 for(auto target:targets)if(target)target->Release();for(auto texture:textures)if(texture)texture->Release();if(context)context->Release();if(device)device->Release();if(adapter)adapter->Release();if(factory)factory->Release();
 FreeLibrary(dll);std::fclose(out);return result?result:(submitted==90?0:14);
}

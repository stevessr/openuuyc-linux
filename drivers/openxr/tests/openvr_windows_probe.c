// Diagnostic only: loads the supplied API DLL; never launches a game.
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
typedef unsigned int (__cdecl *init_fn)(int *,int,const char *);
typedef void (__cdecl *shutdown_fn)(void);
typedef void *(__cdecl *interface_fn)(const char *,int *);
int main(int argc,char **argv) {
 FILE *report=argc==3?fopen(argv[2],"wb"):stdout;
 if(!report)return 6;
 setvbuf(report,NULL,_IONBF,0);
 fprintf(report,"{\"probe_started\":true}\n");
 if(argc!=2 && argc!=3) {fprintf(stderr,"usage: probe.exe PATH_TO_OPENVR_API_DLL\n");return 2;}
 HMODULE dll=LoadLibraryA(argv[1]);
 if(!dll) {fprintf(report,"{\"dll_loaded\":false,\"win32_error\":%lu}\n",GetLastError());return 3;}
 init_fn init=(init_fn)GetProcAddress(dll,"VR_InitInternal2");
 shutdown_fn shutdown=(shutdown_fn)GetProcAddress(dll,"VR_ShutdownInternal");
 if(!init||!shutdown) {fprintf(report,"{\"dll_loaded\":true,\"entrypoints\":false}\n");return 4;}
 int error=-1;unsigned token=init(&error,3,NULL); // Background: must not launch SteamVR.
 fprintf(report,"{\"dll_loaded\":true,\"background_init_error\":%d,\"token\":%u,\"game_started\":false}\n",error,token);
 int missing=0;
 if(error==0) {
  interface_fn get=(interface_fn)GetProcAddress(dll,"VR_GetGenericInterface");
  const char *versions[]={"IVRChaperone_004","IVRCompositor_028","IVROverlayView_003","IVROverlay_027","IVRSystem_022"};
  for(unsigned i=0;i<5;i++){int error=-1;void *iface=get?get(versions[i],&error):NULL;int valid=iface!=NULL && error==0;missing|=!valid;fprintf(report,"{\"interface\":\"%s\",\"available\":%s,\"error\":%d}\n",versions[i],valid?"true":"false",error);}
  shutdown();fprintf(report,"{\"shutdown_complete\":true}\n");}
 FreeLibrary(dll);return error==0?(missing?7:0):5;
}

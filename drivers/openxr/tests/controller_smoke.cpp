// Synthetic tracking/buttons through real OpenXR action binding. Not headset evidence.
#include <time.h>
#define XR_USE_TIMESPEC
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <cstdio>
#include <cstring>
#include <cmath>
#include <unistd.h>
#define OK(call) do {XrResult result=(call);if(XR_FAILED(result)){std::fprintf(stderr,"%s: %d\n",#call,int(result));return 2;}} while(0)
int main(int argc,char **argv) {
 const char *ext[]={"XR_MND_headless","XR_KHR_convert_timespec_time"};
 XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};ci.applicationInfo.apiVersion=XR_MAKE_VERSION(1,0,0);std::strcpy(ci.applicationInfo.applicationName,"OpenUUYC controller fixture");ci.enabledExtensionCount=2;ci.enabledExtensionNames=ext;
 XrInstance instance;OK(xrCreateInstance(&ci,&instance));XrSystemGetInfo gi{XR_TYPE_SYSTEM_GET_INFO};gi.formFactor=XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;XrSystemId system;OK(xrGetSystem(instance,&gi,&system));
 XrPath hands[2],profile;OK(xrStringToPath(instance,"/user/hand/left",&hands[0]));OK(xrStringToPath(instance,"/user/hand/right",&hands[1]));OK(xrStringToPath(instance,"/interaction_profiles/oculus/touch_controller",&profile));
 XrActionSetCreateInfo setinfo{XR_TYPE_ACTION_SET_CREATE_INFO};std::strcpy(setinfo.actionSetName,"fixture");std::strcpy(setinfo.localizedActionSetName,"Fixture");XrActionSet set;OK(xrCreateActionSet(instance,&setinfo,&set));
 XrAction actions[6];const char *names[]={"grip","aim","trigger","stick","button","feedback"};XrActionType types[]={XR_ACTION_TYPE_POSE_INPUT,XR_ACTION_TYPE_POSE_INPUT,XR_ACTION_TYPE_FLOAT_INPUT,XR_ACTION_TYPE_VECTOR2F_INPUT,XR_ACTION_TYPE_BOOLEAN_INPUT,XR_ACTION_TYPE_VIBRATION_OUTPUT};
 for(int i=0;i<6;i++){XrActionCreateInfo ai{XR_TYPE_ACTION_CREATE_INFO};std::strcpy(ai.actionName,names[i]);std::strcpy(ai.localizedActionName,names[i]);ai.actionType=types[i];ai.countSubactionPaths=2;ai.subactionPaths=hands;OK(xrCreateAction(set,&ai,&actions[i]));}
 XrActionSuggestedBinding bindings[12];int b=0;for(int h=0;h<2;h++)for(int i=0;i<5;i++){const char *suffix[]={"grip/pose","aim/pose","trigger/value","thumbstick","x/click"};char path[128];std::snprintf(path,sizeof(path),"/user/hand/%s/input/%s",h?"right":"left",i==4&&h?"a/click":suffix[i]);XrPath p;OK(xrStringToPath(instance,path,&p));bindings[b++]={actions[i],p};}
 for(int h=0;h<2;h++){XrPath p;OK(xrStringToPath(instance,h?"/user/hand/right/output/haptic":"/user/hand/left/output/haptic",&p));bindings[b++]={actions[5],p};}
 XrInteractionProfileSuggestedBinding suggest{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};suggest.interactionProfile=profile;suggest.countSuggestedBindings=12;suggest.suggestedBindings=bindings;OK(xrSuggestInteractionProfileBindings(instance,&suggest));
 XrSessionCreateInfo si{XR_TYPE_SESSION_CREATE_INFO};si.systemId=system;XrSession session;OK(xrCreateSession(instance,&si,&session));XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};attach.countActionSets=1;attach.actionSets=&set;OK(xrAttachSessionActionSets(session,&attach));
 XrReferenceSpaceCreateInfo ri{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};ri.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_LOCAL;ri.poseInReferenceSpace.orientation.w=1;XrSpace local;OK(xrCreateReferenceSpace(session,&ri,&local));
 XrSpace spaces[2][2];for(int h=0;h<2;h++)for(int i=0;i<2;i++){XrActionSpaceCreateInfo ai{XR_TYPE_ACTION_SPACE_CREATE_INFO};ai.action=actions[i];ai.subactionPath=hands[h];ai.poseInActionSpace.orientation.w=1;OK(xrCreateActionSpace(session,&ai,&spaces[h][i]));}
 bool running=false;for(int attempt=0;attempt<100;attempt++){XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};while(xrPollEvent(instance,&event)==XR_SUCCESS){if(event.type==XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED&&reinterpret_cast<XrEventDataSessionStateChanged*>(&event)->state==XR_SESSION_STATE_READY){XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};begin.primaryViewConfigurationType=XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;OK(xrBeginSession(session,&begin));running=true;}event={XR_TYPE_EVENT_DATA_BUFFER};}if(running)break;usleep(10000);}
 if(!running)return 3;
 XrActiveActionSet active{set,XR_NULL_PATH};XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};sync.countActiveActionSets=1;sync.activeActionSets=&active;
 for(int i=0;i<5;i++){OK(xrSyncActions(session,&sync));usleep(20000);}
 PFN_xrConvertTimespecTimeToTimeKHR convert;OK(xrGetInstanceProcAddr(instance,"xrConvertTimespecTimeToTimeKHR",reinterpret_cast<PFN_xrVoidFunction*>(&convert)));timespec now;clock_gettime(CLOCK_MONOTONIC,&now);XrTime time;OK(convert(instance,&now,&time));
 for(int h=0;h<2;h++){
  for(int i=0;i<2;i++){XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};OK(xrLocateSpace(spaces[h][i],local,time,&loc));float expected=float((i?3:1)+h);if((loc.locationFlags & (XR_SPACE_LOCATION_POSITION_VALID_BIT|XR_SPACE_LOCATION_POSITION_TRACKED_BIT|XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT)) != (XR_SPACE_LOCATION_POSITION_VALID_BIT|XR_SPACE_LOCATION_POSITION_TRACKED_BIT|XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT)||std::fabs(loc.pose.position.x-expected)>.001){std::fprintf(stderr,"pose h=%d i=%d flags=%llu x=%f expected=%f\n",h,i,(unsigned long long)loc.locationFlags,loc.pose.position.x,expected);return 4;}}
  XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO};get.subactionPath=hands[h];get.action=actions[2];XrActionStateFloat trigger{XR_TYPE_ACTION_STATE_FLOAT};OK(xrGetActionStateFloat(session,&get,&trigger));if(!trigger.isActive||std::fabs(trigger.currentState-.7f)>.001)return 5;
  get.action=actions[3];XrActionStateVector2f stick{XR_TYPE_ACTION_STATE_VECTOR2F};OK(xrGetActionStateVector2f(session,&get,&stick));if(!stick.isActive||std::fabs(stick.currentState.x+.25f)>.001||std::fabs(stick.currentState.y-.75f)>.001)return 6;
  get.action=actions[4];XrActionStateBoolean button{XR_TYPE_ACTION_STATE_BOOLEAN};OK(xrGetActionStateBoolean(session,&get,&button));if(!button.isActive||!button.currentState)return 7;
 }
 if(argc>2) {
  XrReferenceSpaceCreateInfo ground{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};ground.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_STAGE;ground.poseInReferenceSpace.orientation.w=1;XrSpace stage;OK(xrCreateReferenceSpace(session,&ground,&stage));
  ground.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_VIEW;XrSpace view;OK(xrCreateReferenceSpace(session,&ground,&view));
  XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};OK(xrLocateSpace(view,stage,time,&location));
  if(!(location.locationFlags&XR_SPACE_LOCATION_POSITION_VALID_BIT)||std::fabs(location.pose.position.y-1.6f)>.001f)return 12;
  xrDestroySpace(view);xrDestroySpace(stage);
 }
 for(int h=0;h<2;h++) {
  XrHapticActionInfo output{XR_TYPE_HAPTIC_ACTION_INFO};output.action=actions[5];output.subactionPath=hands[h];
  XrHapticVibration pulse{XR_TYPE_HAPTIC_VIBRATION};pulse.amplitude=.5f;pulse.frequency=120;pulse.duration=2000000000LL;
  OK(xrApplyHapticFeedback(session,&output,reinterpret_cast<XrHapticBaseHeader*>(&pulse)));usleep(50000);
  OK(xrStopHapticFeedback(session,&output));usleep(50000);
  pulse.amplitude=.25f;pulse.frequency=XR_FREQUENCY_UNSPECIFIED;pulse.duration=XR_MIN_HAPTIC_DURATION;
  OK(xrApplyHapticFeedback(session,&output,reinterpret_cast<XrHapticBaseHeader*>(&pulse)));usleep(50000);
 }
 if(argc>1) {
  FILE *marker=std::fopen(argv[1],"w");if(!marker)return 8;std::fputs("release",marker);std::fclose(marker);
  usleep(100000);OK(xrSyncActions(session,&sync));
  for(int h=0;h<2;h++) {
   XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO};get.subactionPath=hands[h];get.action=actions[2];XrActionStateFloat trigger{XR_TYPE_ACTION_STATE_FLOAT};OK(xrGetActionStateFloat(session,&get,&trigger));if(trigger.isActive||trigger.currentState!=0)return 9;
   get.action=actions[4];XrActionStateBoolean button{XR_TYPE_ACTION_STATE_BOOLEAN};OK(xrGetActionStateBoolean(session,&get,&button));if(button.isActive||button.currentState)return 10;
   clock_gettime(CLOCK_MONOTONIC,&now);OK(convert(instance,&now,&time));XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};OK(xrLocateSpace(spaces[h][0],local,time,&loc));if(loc.locationFlags&XR_SPACE_LOCATION_POSITION_VALID_BIT)return 11;
  }
 }
 std::puts("{\"real_openxr_controller_actions\":true,\"independent_grip_aim\":true,\"analog_trigger_stick\":true,\"tracking_loss_releases_inputs\":true,\"headset_verified\":false}");
 for(auto& hand:spaces)for(auto s:hand)xrDestroySpace(s);xrDestroySpace(local);xrDestroySession(session);xrDestroyActionSet(set);xrDestroyInstance(instance);return 0;
}

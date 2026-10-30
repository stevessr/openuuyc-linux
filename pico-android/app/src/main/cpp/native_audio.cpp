#include "native_audio_ring.h"
#include <aaudio/AAudio.h>
#include <jni.h>
#include <opus.h>
#include <algorithm>
#include <chrono>
#include <memory>
#include <cmath>
#include <thread>
namespace {
struct Player {
 ouaudio::Ring ring;AAudioStream*stream=nullptr;OpusDecoder*decoder=nullptr;
 uint32_t epoch=0,lastTimestamp=0;int lastCodec=-1;bool timeline=false;
 std::atomic<bool> failed{false};uint64_t packets=0,rejected=0,decodeNs=0,gaps=0;
 int burst=192,buffer=384;
 ~Player(){if(stream){AAudioStream_requestStop(stream);AAudioStream_close(stream);}if(decoder)opus_decoder_destroy(decoder);}
 static aaudio_data_callback_result_t callback(AAudioStream*,void*user,void*data,int32_t frames){
  auto*p=static_cast<Player*>(user);p->ring.pull(static_cast<float*>(data),uint32_t(frames));return AAUDIO_CALLBACK_RESULT_CONTINUE;
 }
 static void error(AAudioStream*,void*user,aaudio_result_t){static_cast<Player*>(user)->failed.store(true,std::memory_order_release);}
 bool open(uint32_t value){
  epoch=value;int errorCode=0;decoder=opus_decoder_create(48000,2,&errorCode);if(!decoder||errorCode!=OPUS_OK)return false;
  for(auto sharing:{AAUDIO_SHARING_MODE_EXCLUSIVE,AAUDIO_SHARING_MODE_SHARED}){
   AAudioStreamBuilder*b=nullptr;if(AAudio_createStreamBuilder(&b)!=AAUDIO_OK)return false;
   AAudioStreamBuilder_setDirection(b,AAUDIO_DIRECTION_OUTPUT);AAudioStreamBuilder_setSampleRate(b,48000);
   AAudioStreamBuilder_setChannelCount(b,2);AAudioStreamBuilder_setFormat(b,AAUDIO_FORMAT_PCM_FLOAT);
   AAudioStreamBuilder_setUsage(b,AAUDIO_USAGE_GAME);AAudioStreamBuilder_setPerformanceMode(b,AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
   AAudioStreamBuilder_setSharingMode(b,sharing);AAudioStreamBuilder_setDataCallback(b,callback,this);AAudioStreamBuilder_setErrorCallback(b,error,this);
   const auto result=AAudioStreamBuilder_openStream(b,&stream);AAudioStreamBuilder_delete(b);
   if(result==AAUDIO_OK&&stream)break;stream=nullptr;
  }
  if(!stream||AAudioStream_getSampleRate(stream)!=48000||AAudioStream_getChannelCount(stream)!=2||AAudioStream_getFormat(stream)!=AAUDIO_FORMAT_PCM_FLOAT||AAudioStream_getPerformanceMode(stream)!=AAUDIO_PERFORMANCE_MODE_LOW_LATENCY)return false;
  burst=AAudioStream_getFramesPerBurst(stream);if(burst<=0||burst>1024)return false;
  tune(2);return AAudioStream_requestStart(stream)==AAUDIO_OK;
 }
 void tune(int bursts){const auto result=AAudioStream_setBufferSizeInFrames(stream,burst*std::clamp(bursts,1,4));if(result>0)buffer=result;ring.target.store(uint32_t(std::clamp(buffer,120,2048)),std::memory_order_release);}
 bool feed(const uint8_t*data,size_t size){
  ouaudio::Header h{};if(!ouaudio::parse(data,size,h)||h.epoch!=epoch){rejected++;return false;}
  if(ring.reset.load(std::memory_order_acquire))return false;
  if(timeline){
   const int32_t delta=static_cast<int32_t>(h.timestamp-(lastTimestamp+120));
   if(delta<0){rejected++;return false;}
   if(h.reset||delta>480||delta%120){ring.reset.store(true,std::memory_order_release);timeline=false;lastCodec=-1;}
   else for(int32_t gap=0;gap<delta;gap+=120){float silence[240]{};if(lastCodec==1 && opus_decode_float(decoder,nullptr,0,silence,120,0)<0)std::fill_n(silence,240,0.0f);ring.push(silence,120);gaps++;}
  }
  if(lastCodec!=h.codec){opus_decoder_ctl(decoder,OPUS_RESET_STATE);lastCodec=h.codec;}
  float pcm[240];const auto started=std::chrono::steady_clock::now();
  if(h.codec==0){for(int i=0;i<240;i++){const uint16_t bits=uint16_t(data[24+i*2])|uint16_t(data[25+i*2])<<8;pcm[i]=static_cast<int16_t>(bits)/32768.0f;}}
  else if(opus_decode_float(decoder,data+24,h.length,pcm,120,0)!=120){rejected++;return false;}
  for(auto&sample:pcm)sample=std::isfinite(sample)?std::clamp(sample,-1.0f,1.0f):0.0f;
  decodeNs+=uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-started).count());
  lastTimestamp=h.timestamp;timeline=true;packets++;return ring.push(pcm,120);
 }
};
Player*player(jlong handle){return reinterpret_cast<Player*>(handle);}
}
extern "C" JNIEXPORT jlong JNICALL Java_org_openuuyc_pico_uu_UuNativeAudio_nativeOpen(JNIEnv*,jclass,jint epoch){
 try {auto p=std::make_unique<Player>();if(!p->open(uint32_t(epoch)))return 0;return reinterpret_cast<jlong>(p.release());}catch(...){return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_org_openuuyc_pico_uu_UuNativeAudio_nativeClose(JNIEnv*,jclass,jlong h){delete player(h);}
extern "C" JNIEXPORT void JNICALL Java_org_openuuyc_pico_uu_UuNativeAudio_nativeArm(JNIEnv*,jclass,jlong h,jboolean value){if(h)player(h)->ring.armed.store(value,std::memory_order_release);}
extern "C" JNIEXPORT void JNICALL Java_org_openuuyc_pico_uu_UuNativeAudio_nativeTune(JNIEnv*,jclass,jlong h,jint bursts){if(h)player(h)->tune(bursts);}
extern "C" JNIEXPORT jboolean JNICALL Java_org_openuuyc_pico_uu_UuNativeAudio_nativeFeed(JNIEnv*env,jclass,jlong h,jbyteArray value){
 if(!h)return false;const auto n=env->GetArrayLength(value);if(n<24||n>1224)return false;
 uint8_t data[1224];env->GetByteArrayRegion(value,0,n,reinterpret_cast<jbyte*>(data));return player(h)->feed(data,size_t(n));
}
extern "C" JNIEXPORT jlongArray JNICALL Java_org_openuuyc_pico_uu_UuNativeAudio_nativeStats(JNIEnv*env,jclass,jlong h){
 if(!h)return nullptr;auto&p=*player(h);
 const jlong values[]={jlong(p.packets),jlong(p.ring.played.load()),jlong(p.ring.underruns.load()),jlong(p.ring.dropped.load()),jlong(p.ring.queued()),jlong(p.failed.load()),p.burst,p.buffer,jlong(p.packets?p.decodeNs/p.packets:0),jlong(p.gaps),jlong(p.rejected),AAudioStream_getXRunCount(p.stream),jlong(p.ring.callbacks.load())};
 auto result=env->NewLongArray(13);if(result)env->SetLongArrayRegion(result,0,13,values);return result;
}
// Debug instrumentation: generated silence only, no network or microphone.
extern "C" JNIEXPORT jlongArray JNICALL Java_org_openuuyc_pico_uu_UuNativeAudio_nativeProbe(JNIEnv*env,jobject){
 Player p;jlong values[7]={0,0,0,0,0,0,0};
 if(p.open(12345)) {
  int error=0;OpusEncoder*encoder=opus_encoder_create(48000,2,OPUS_APPLICATION_RESTRICTED_LOWDELAY,&error);
  if(encoder&&error==OPUS_OK){
   float zeros[240]{};uint8_t packet[1224]{};
   auto put=[&](int offset,uint32_t value){for(int i=0;i<4;i++)packet[offset+i]=uint8_t(value>>(i*8));};
   std::memcpy(packet,"OUA1",4);packet[4]=1;packet[6]=120;put(8,12345);
   const auto anchor=std::chrono::steady_clock::now();
   for(uint32_t i=0;i<160;i++){
    packet[5]=i<80?0:1;put(12,i+1);put(16,i*120);
    int n=480;if(packet[5])n=opus_encode_float(encoder,zeros,120,packet+24,1200);else std::memset(packet+24,0,480);
    if(n>0){put(20,uint32_t(n));p.feed(packet,24+size_t(n));}
    if(p.ring.callbacks.load()>0 && p.ring.queued()>=uint32_t(p.burst*2))p.ring.armed.store(true);
    std::this_thread::sleep_until(anchor+std::chrono::microseconds((i+1)*2500));
   }
   opus_encoder_destroy(encoder);
   values[0]=1;values[1]=jlong(p.packets);values[2]=jlong(p.ring.played.load());values[3]=p.burst;values[4]=p.buffer;values[5]=jlong(p.ring.underruns.load());values[6]=AAudioStream_getPerformanceMode(p.stream);
  }
 }
 auto result=env->NewLongArray(7);if(result)env->SetLongArrayRegion(result,0,7,values);return result;
}

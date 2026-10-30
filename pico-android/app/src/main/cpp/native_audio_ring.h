#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
namespace ouaudio {
constexpr uint32_t kCapacity=4096;
struct Header {uint8_t codec;bool reset;uint16_t frames;uint32_t epoch,sequence,timestamp,length;};
inline uint32_t le32(const uint8_t*p){return uint32_t(p[0])|uint32_t(p[1])<<8|uint32_t(p[2])<<16|uint32_t(p[3])<<24;}
inline bool parse(const uint8_t*p,size_t n,Header&h){
 if(n<24||std::memcmp(p,"OUA1",4)||p[4]!=1||(p[5]&0x7e))return false;
 h={uint8_t(p[5]&1),bool(p[5]&128),uint16_t(p[6]|p[7]<<8),le32(p+8),le32(p+12),le32(p+16),le32(p+20)};
 return h.epoch && h.frames==120 && h.length>0 && h.length<=1200 && n==24+h.length && (h.codec!=0||h.length==480);
}
// One ingress producer, one AAudio consumer. Only the consumer moves read.
struct Ring {
 std::array<float,kCapacity*2> samples{};
 std::atomic<uint32_t> read{0},write{0},target{384};
 std::atomic<bool> reset{false},armed{false},discontinuity{false};
 std::atomic<uint64_t> played{0},underruns{0},dropped{0},callbacks{0};
 float last[2]{},fadeFrom[2]{};
 uint32_t fadeRemaining=0;
 uint32_t queued()const{const auto r=read.load(std::memory_order_acquire);return write.load(std::memory_order_acquire)-r;}
 void beginFade(){fadeFrom[0]=last[0];fadeFrom[1]=last[1];fadeRemaining=120;}
 void silence(float*out,uint32_t frames){
  std::memset(out,0,frames*2*sizeof(float));
  const auto count=std::min(frames,120u);
  for(uint32_t i=0;i<count;i++){
   const auto gain=float(count-i-1)/float(count);
   out[i*2]=last[0]*gain;out[i*2+1]=last[1]*gain;
  }
  fadeFrom[0]=fadeFrom[1]=0.0f;fadeRemaining=120;
  if(frames){last[0]=out[(frames-1)*2];last[1]=out[(frames-1)*2+1];}
 }
 bool push(const float*pcm,uint32_t frames){
  if(reset.load(std::memory_order_acquire))return false;
  const auto w=write.load(std::memory_order_relaxed),r=read.load(std::memory_order_acquire);
  if(frames>kCapacity||w-r>kCapacity-frames){dropped.fetch_add(frames,std::memory_order_relaxed);discontinuity.store(true,std::memory_order_release);return false;}
  for(uint32_t i=0;i<frames;i++)for(int c=0;c<2;c++)samples[((w+i)&(kCapacity-1))*2+c]=pcm[i*2+c];
  write.store(w+frames,std::memory_order_release);return true;
 }
 void pull(float*out,uint32_t frames){
  callbacks.fetch_add(1,std::memory_order_relaxed);
  auto r=read.load(std::memory_order_relaxed);const auto w=write.load(std::memory_order_acquire);
  if(reset.exchange(false,std::memory_order_acq_rel)){r=w;read.store(r,std::memory_order_release);beginFade();}
  if(discontinuity.exchange(false,std::memory_order_acq_rel))beginFade();
  const auto desired=target.load(std::memory_order_relaxed);
  if(w-r>desired+frames){const auto skip=w-r-desired;r+=skip;dropped.fetch_add(skip,std::memory_order_relaxed);beginFade();}
  if(!armed.load(std::memory_order_acquire)){read.store(r,std::memory_order_release);silence(out,frames);return;}
  const auto count=frames<(w-r)?frames:w-r;
  for(uint32_t i=0;i<count;i++)for(int c=0;c<2;c++)out[i*2+c]=samples[((r+i)&(kCapacity-1))*2+c];
  const auto fade=std::min(count,fadeRemaining);
  for(uint32_t i=0;i<fade;i++){
   const auto amount=float(120u-fadeRemaining+i+1)/120.0f;
   out[i*2]=fadeFrom[0]*(1.0f-amount)+out[i*2]*amount;
   out[i*2+1]=fadeFrom[1]*(1.0f-amount)+out[i*2+1]*amount;
  }
  fadeRemaining-=fade;
  if(count<frames){
   if(count==0)silence(out,frames);
   else{
    std::memset(out+count*2,0,(frames-count)*2*sizeof(float));
    const auto ramp=std::min(count,120u);
    for(uint32_t i=0;i<ramp;i++){
     const auto gain=float(ramp-i-1)/float(ramp);
     out[(count-ramp+i)*2]*=gain;out[(count-ramp+i)*2+1]*=gain;
    }
    fadeFrom[0]=fadeFrom[1]=0.0f;fadeRemaining=120;
    if(frames){last[0]=out[(frames-1)*2];last[1]=out[(frames-1)*2+1];}
   }
   underruns.fetch_add(1,std::memory_order_relaxed);
  }else if(frames){last[0]=out[(frames-1)*2];last[1]=out[(frames-1)*2+1];}
  read.store(r+count,std::memory_order_release);played.fetch_add(count,std::memory_order_relaxed);
 }
};
}

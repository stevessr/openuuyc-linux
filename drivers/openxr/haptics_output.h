// Optional bounded local output. No device/network access outside the authorized host socket.
#pragma once
#include <sys/socket.h>
#include <sys/un.h>
struct ouhp_packet {char magic[4];uint32_t version;uint64_t generation,timestamp;uint32_t hand;float amplitude,frequency;uint32_t duration_ms;};
_Static_assert(sizeof(struct ouhp_packet)==40,"haptic wire ABI");
static xrt_result_t controller_output(struct xrt_device *device,enum xrt_output_name name,const struct xrt_output_value *value) {
    if(name!=XRT_OUTPUT_NAME_TOUCH_HAPTIC)return XRT_ERROR_OUTPUT_UNSUPPORTED;
    if(!value)return XRT_SUCCESS;
    if(value->type!=XRT_OUTPUT_VALUE_TYPE_VIBRATION)return XRT_ERROR_OUTPUT_UNSUPPORTED;
    struct pico_controller *c=(struct pico_controller*)device;struct ouxd_snapshot frame;
    if(!controller_frame(c,&frame))return XRT_SUCCESS;
    float amplitude=value->vibration.amplitude,frequency=value->vibration.frequency;
    if(!isfinite(amplitude)||amplitude<0||amplitude>1||!isfinite(frequency)||frequency<0||frequency>1000)return XRT_SUCCESS;
    int64_t ns=value->vibration.duration_ns;
    uint32_t duration_ms=ns<=0?1:(uint32_t)(ns>1000000000LL?1000:(ns+999999)/1000000);
    if(amplitude<=0 || duration_ms==0) {amplitude=0;duration_ms=0;}
    struct ouhp_packet packet={{'O','U','H','P'},1,frame.generation,(uint64_t)os_monotonic_get_ns(),(uint32_t)c->hand,amplitude,frequency,duration_ms};
    const char *root=getenv("XDG_RUNTIME_DIR");if(!root)return XRT_SUCCESS;
    struct sockaddr_un address;memset(&address,0,sizeof(address));address.sun_family=AF_UNIX;
    if(snprintf(address.sun_path,sizeof(address.sun_path),"%s/openuuyc-pcvr/openxr/haptics.sock",root)>=(int)sizeof(address.sun_path))return XRT_SUCCESS;
    int fd=socket(AF_UNIX,SOCK_DGRAM|SOCK_NONBLOCK|SOCK_CLOEXEC,0);if(fd<0)return XRT_SUCCESS;
    (void)sendto(fd,&packet,sizeof(packet),MSG_DONTWAIT,(struct sockaddr*)&address,sizeof(address));close(fd);
    return XRT_SUCCESS;
}

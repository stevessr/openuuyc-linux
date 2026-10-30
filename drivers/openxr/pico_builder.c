// OpenUUYC Monado device builder. No simulated poses, display size, IPD or FOV.
#include "xrt/xrt_device.h"
#include "xrt/xrt_prober.h"
#include "xrt/xrt_system.h"
#include "xrt/xrt_space.h"
#include "xrt/xrt_tracking.h"
#include "util/u_misc.h"
#include "util/u_device.h"
#include "util/u_distortion_mesh.h"
#include "util/u_system_devices.h"
#include "b_space_overseer.h"
#include "b_io_cache.h"
#include "target_builder_interface.h"
#include "os/os_time.h"
#include "device_ipc.h"
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <pthread.h>

struct pico_map {int fd;const volatile struct ouxd_snapshot *data;};
struct pico_hmd {struct xrt_device base;struct pico_map map;struct ouxd_snapshot calibration;pthread_mutex_t mutex;int64_t last_reopen;};
static void close_map(struct pico_map *map) {if(map->data) munmap((void*)map->data,OUXD_MAP_BYTES);if(map->fd>=0) close(map->fd);map->fd=-1;map->data=NULL;}
static int open_map(struct pico_map *map) {
    map->fd=-1;map->data=NULL;const char *root=getenv("XDG_RUNTIME_DIR");if(!root) return 0;
    char path[4096];if(snprintf(path,sizeof(path),"%s/openuuyc-pcvr/openxr/device.shm",root)>=(int)sizeof(path)) return 0;
    int fd=open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);if(fd<0) return 0;
    struct stat st;if(fstat(fd,&st) || !S_ISREG(st.st_mode) || st.st_uid!=getuid() || (st.st_mode&077) || st.st_size!=OUXD_MAP_BYTES) {close(fd);return 0;}
    void *data=mmap(NULL,OUXD_MAP_BYTES,PROT_READ,MAP_SHARED,fd,0);if(data==MAP_FAILED) {close(fd);return 0;}
    map->fd=fd;map->data=data;return 1;
}
static int snapshot(struct pico_map *map,struct ouxd_snapshot *out) {
    if(!map->data || !ouxd_copy(map->data,out) || memcmp(out->magic,"OUXD",4) || out->version!=1 || !out->generation) return 0;
    int64_t now=os_monotonic_get_ns();
    if(out->received_ns<=0 || out->received_ns>now || now-out->received_ns>250000000) return 0;
    if(out->eye_width<64 || out->eye_width>4096 || out->eye_height<64 || out->eye_height>4096 || out->eye_width%2 || out->eye_height%2 || !isfinite(out->refresh_hz) || out->refresh_hz<30 || out->refresh_hz>165) return 0;
    float ipd2=0;
    for(int eye=0;eye<2;++eye) {
        float *v=out->views+eye*11;float norm=0;
        for(int i=0;i<11;++i) if(!isfinite(v[i])) return 0;
        for(int i=0;i<3;++i) if(fabsf(v[i])>=.2f) return 0;
        for(int i=3;i<7;++i) {norm+=v[i]*v[i];}
        if(norm<.98f || norm>1.02f) return 0;
        for(int i=7;i<11;++i) if(fabsf(v[i])>=1.56f) return 0;
        if(v[8]-v[7]<=.05f || v[9]-v[10]<=.05f) return 0;
    }
    for(int i=0;i<3;++i) {float d=out->views[i]-out->views[11+i];ipd2+=d*d;}
    if(ipd2<.0009f || ipd2>.01f || memcmp(out->tracking,"OUVR",4)) return 0;
    uint32_t version,flags;memcpy(&version,out->tracking+4,4);memcpy(&flags,out->tracking+32,4);
    if(version!=1 || (flags&3)!=3) return 0;
    float head[13];memcpy(head,out->tracking+40,sizeof(head));float norm=0;
    for(int i=0;i<13;++i) if(!isfinite(head[i]) || fabsf(head[i])>1000) return 0;
    for(int i=3;i<7;++i) {norm+=head[i]*head[i];}
    return norm>=.98f && norm<=1.02f;
}
static int same_calibration(const struct ouxd_snapshot *a,const struct ouxd_snapshot *b) {
    if(a->eye_width!=b->eye_width || a->eye_height!=b->eye_height ||
       fabsf(a->refresh_hz-b->refresh_hz)>.1f) return 0;
    for(int i=0;i<22;i++) if(fabsf(a->views[i]-b->views[i])>.0001f) return 0;
    return 1;
}
/* Existing applications retain their XrInstance/XrSession. Reopen only a fresh,
 * owner-validated publisher with identical optical parameters; never fabricate
 * a new HMD or change a running application's projection configuration. */
static int refresh_hmd(struct pico_hmd *h,struct ouxd_snapshot *frame) {
    if(snapshot(&h->map,frame)) {
        if(frame->generation==h->calibration.generation) return 1;
        if(same_calibration(&h->calibration,frame)) {
            h->calibration.generation=frame->generation;return 1;
        }
        return 0;
    }
    int64_t now=os_monotonic_get_ns();
    if(now-h->last_reopen<100000000) return 0;
    h->last_reopen=now;
    struct pico_map next;
    if(!open_map(&next)) return 0;
    if(!snapshot(&next,frame) || !same_calibration(&h->calibration,frame)) {
        close_map(&next);return 0;
    }
    close_map(&h->map);h->map=next;h->calibration.generation=frame->generation;
    return 1;
}
static void destroy_hmd(struct xrt_device *device) {struct pico_hmd *h=(struct pico_hmd*)device;close_map(&h->map);pthread_mutex_destroy(&h->mutex);u_device_free(device);}
static xrt_result_t tracked(struct xrt_device *device,enum xrt_input_name name,int64_t at,struct xrt_space_relation *out) {
    (void)at;memset(out,0,sizeof(*out));out->pose.orientation.w=1;
    if(name!=XRT_INPUT_GENERIC_HEAD_POSE) return XRT_ERROR_INPUT_UNSUPPORTED;
    struct pico_hmd *h=(struct pico_hmd*)device;struct ouxd_snapshot frame;
    pthread_mutex_lock(&h->mutex);
    int available=refresh_hmd(h,&frame);
    pthread_mutex_unlock(&h->mutex);
    if(!available) return XRT_SUCCESS;
    float p[13];uint32_t flags;memcpy(&flags,frame.tracking+32,4);memcpy(p,frame.tracking+40,sizeof(p));
    out->pose.position=(struct xrt_vec3){p[0],p[1],p[2]};out->pose.orientation=(struct xrt_quat){p[3],p[4],p[5],p[6]};
    out->relation_flags=XRT_SPACE_RELATION_ORIENTATION_VALID_BIT|XRT_SPACE_RELATION_POSITION_VALID_BIT;
    if(flags&4) {out->linear_velocity=(struct xrt_vec3){p[7],p[8],p[9]};out->relation_flags|=XRT_SPACE_RELATION_LINEAR_VELOCITY_VALID_BIT;}
    if(flags&8) {out->angular_velocity=(struct xrt_vec3){p[10],p[11],p[12]};out->relation_flags|=XRT_SPACE_RELATION_ANGULAR_VELOCITY_VALID_BIT;}
    return XRT_SUCCESS;
}
static xrt_result_t views(struct xrt_device *device,const struct xrt_vec3 *unused,int64_t at,enum xrt_view_type type,uint32_t count,struct xrt_space_relation *head,struct xrt_fov *fov,struct xrt_pose *poses) {
    (void)unused;(void)type;if(count!=2) return XRT_ERROR_INPUT_UNSUPPORTED;
    tracked(device,XRT_INPUT_GENERIC_HEAD_POSE,at,head);
    struct pico_hmd *h=(struct pico_hmd*)device;
    for(int eye=0;eye<2;++eye) {const float *v=h->calibration.views+eye*11;poses[eye]=(struct xrt_pose){{v[3],v[4],v[5],v[6]},{v[0],v[1],v[2]}};fov[eye]=(struct xrt_fov){v[7],v[8],v[9],v[10]};}
    return XRT_SUCCESS;
}
static struct xrt_device *create_hmd(void) {
    struct pico_map map;struct ouxd_snapshot initial;if(!open_map(&map)) return NULL;if(!snapshot(&map,&initial)) {close_map(&map);return NULL;}
    struct pico_hmd *h=U_DEVICE_ALLOCATE(struct pico_hmd,U_DEVICE_ALLOC_HMD|U_DEVICE_ALLOC_TRACKING_NONE,1,0);
    if(!h) {close_map(&map);return NULL;}
    if(pthread_mutex_init(&h->mutex,NULL)) {close_map(&map);u_device_free(&h->base);return NULL;}
    h->map=map;h->calibration=initial;struct xrt_device *d=&h->base;
    u_device_populate_function_pointers(d,tracked,destroy_hmd);d->get_view_poses=views;
    d->name=XRT_DEVICE_GENERIC_HMD;d->device_type=XRT_DEVICE_TYPE_HMD;d->supported.orientation_tracking=true;d->supported.position_tracking=true;
    d->inputs[0].name=XRT_INPUT_GENERIC_HEAD_POSE;d->inputs[0].active=true;
    snprintf(d->str,sizeof(d->str),"OpenUUYC PICO OpenXR");snprintf(d->serial,sizeof(d->serial),"OpenUUYC-PICO");
    snprintf(d->tracking_origin->name,sizeof(d->tracking_origin->name),"PICO LOCAL");d->tracking_origin->type=XRT_TRACKING_TYPE_RGB;
    d->hmd->view_count=2;d->hmd->screens[0].w_pixels=initial.eye_width*2;d->hmd->screens[0].h_pixels=initial.eye_height;d->hmd->screens[0].nominal_frame_interval_ns=(uint64_t)(1e9/initial.refresh_hz);
    d->hmd->blend_mode_count=1;d->hmd->blend_modes[0]=XRT_BLEND_MODE_OPAQUE;
    for(int eye=0;eye<2;++eye) {struct xrt_view *v=&d->hmd->views[eye];v->viewport.x_pixels=eye*initial.eye_width;v->viewport.y_pixels=0;v->viewport.w_pixels=initial.eye_width;v->viewport.h_pixels=initial.eye_height;v->display.w_pixels=initial.eye_width;v->display.h_pixels=initial.eye_height;v->rot=u_device_rotation_ident;const float *f=initial.views+eye*11+7;d->hmd->distortion.fov[eye]=(struct xrt_fov){f[0],f[1],f[2],f[3]};}
    u_distortion_mesh_set_none(d);return d;
}
static xrt_result_t roles(struct xrt_system_devices *system,struct xrt_system_roles *out) {(void)system;struct xrt_system_roles r=XRT_SYSTEM_ROLES_INIT;r.generation_id=1;*out=r;return XRT_SUCCESS;}
static void destroy_system(struct xrt_system_devices *system) {for(uint32_t i=0;i<system->static_xdev_count;++i) xrt_device_destroy(&system->static_xdevs[i]);free(system);}
static xrt_result_t estimate(struct xrt_builder *builder,cJSON *config,struct xrt_prober *prober,struct xrt_builder_estimate *out) {
    (void)builder;(void)config;(void)prober;memset(out,0,sizeof(*out));struct pico_map map;struct ouxd_snapshot frame;int available=open_map(&map);if(available) {available=snapshot(&map,&frame);close_map(&map);}out->certain.head=available;out->priority=100;return XRT_SUCCESS;
}
static xrt_result_t open_system(struct xrt_builder *builder,cJSON *config,struct xrt_prober *prober,struct xrt_session_event_sink *events,struct xrt_system_devices **out_system,struct xrt_space_overseer **out_space) {
    (void)builder;(void)config;(void)prober;
    struct xrt_device *head=create_hmd();if(!head) return XRT_ERROR_DEVICE_CREATION_FAILED;
    struct xrt_system_devices *system=U_TYPED_CALLOC(struct xrt_system_devices);u_system_devices_populate_function_pointers(system,roles,destroy_system);system->create_io_cache=b_io_cache_create;system->static_xdevs[0]=head;system->static_xdev_count=1;system->static_roles.head=head;
    struct b_space_overseer *bso=b_space_overseer_create(events);struct xrt_space_overseer *space=(struct xrt_space_overseer*)bso;
    struct xrt_space *offset=NULL;b_space_overseer_create_offset_space(bso,space->semantic.root,&head->tracking_origin->initial_offset,&offset);
    b_space_overseer_link_space_to_device(bso,offset,head);xrt_space_reference(&offset,NULL);
    // Headset LOCAL is not a measured floor: do not invent STAGE/local-floor height.
    struct xrt_pose identity=XRT_POSE_IDENTITY;b_space_overseer_create_offset_space(bso,space->semantic.root,&identity,&space->semantic.local);
    b_space_overseer_create_pose_space(bso,head,XRT_INPUT_GENERIC_HEAD_POSE,&space->semantic.view);
    *out_system=system;*out_space=space;return XRT_SUCCESS;
}
static void destroy_builder(struct xrt_builder *builder) {free(builder);}
struct xrt_builder *t_builder_openuuyc_create(void) {
    static const char *drivers[]={"openuuyc"};struct xrt_builder *builder=U_TYPED_CALLOC(struct xrt_builder);
    builder->identifier="openuuyc";builder->name="OpenUUYC authenticated PICO";builder->driver_identifiers=drivers;builder->driver_identifier_count=1;builder->estimate_system=estimate;builder->open_system=open_system;builder->destroy=destroy_builder;return builder;
}

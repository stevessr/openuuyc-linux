// OpenUUYC controller devices. Touch-compatible logical input profile; no fake poses/touches.
#pragma once
struct pico_controller {struct xrt_device base;struct pico_hmd *head;int hand;};
static int controller_frame(struct pico_controller *c,struct ouxd_snapshot *frame) {
    pthread_mutex_lock(&c->head->mutex);int available=refresh_hmd(c->head,frame);pthread_mutex_unlock(&c->head->mutex);
    if(!available || frame->version!=2) return 0;
    uint32_t version;memcpy(&version,frame->tracking+4,4);if(version!=2) return 0;
    const uint8_t *control=frame->tracking+352+c->hand*24;
    uint32_t flags,buttons;float axes[4];memcpy(&flags,control,4);memcpy(&buttons,control+4,4);memcpy(axes,control+8,16);
    if(flags>127 || buttons>15) return 0;
    for(int i=0;i<4;i++) if(!isfinite(axes[i]) || axes[i]<(i<2?0.f:-1.f) || axes[i]>1.f) return 0;
    for(int i=0;i<2;i++) {
        const uint8_t *device=frame->tracking+32+(i?3+c->hand:1+c->hand)*64;
        uint32_t pose_flags;float pose[13];memcpy(&pose_flags,device,4);memcpy(pose,device+8,sizeof(pose));
        if(pose_flags>63) return 0;
        float norm=0;for(int j=0;j<13;j++) {if(!isfinite(pose[j]) || fabsf(pose[j])>1000.f) return 0;if(j>=3&&j<7)norm+=pose[j]*pose[j];}
        if((pose_flags&1) && (norm<.98f || norm>1.02f)) return 0;
    }
    return 1;
}
static uint32_t controller_pose_flags(const struct ouxd_snapshot *frame,int index) {uint32_t flags;memcpy(&flags,frame->tracking+32+index*64,4);return flags;}
static xrt_result_t controller_inputs(struct xrt_device *device) {
    struct pico_controller *c=(struct pico_controller*)device;struct ouxd_snapshot frame;
    int available=controller_frame(c,&frame);uint32_t flags=0,buttons=0;float axes[4]={0};
    int grip_valid=available && (controller_pose_flags(&frame,1+c->hand)&3)==3;
    int aim_valid=available && (controller_pose_flags(&frame,3+c->hand)&3)==3;
    if(available) {const uint8_t *control=frame.tracking+352+c->hand*24;memcpy(&flags,control,4);memcpy(&buttons,control+4,4);memcpy(axes,control+8,16);}
    for(uint32_t i=0;i<device->input_count;i++) {device->inputs[i].active=false;device->inputs[i].timestamp=available?frame.received_ns:os_monotonic_get_ns();memset(&device->inputs[i].value,0,sizeof(device->inputs[i].value));}
    device->inputs[0].active=grip_valid;device->inputs[1].active=aim_valid;
    for(int i=0;i<7;i++) device->inputs[i+2].active=grip_valid && (flags&(1u<<i)) && (i!=6 || c->hand==0);
    if(device->inputs[2].active)device->inputs[2].value.vec1.x=axes[0];
    if(device->inputs[3].active)device->inputs[3].value.vec1.x=axes[1];
    if(device->inputs[4].active)device->inputs[4].value.vec2=(struct xrt_vec2){axes[2],axes[3]};
    for(int i=0;i<4;i++) if(device->inputs[i+5].active)device->inputs[i+5].value.boolean=(buttons&(1u<<i))!=0;
    return XRT_SUCCESS;
}
static xrt_result_t controller_pose(struct xrt_device *device,enum xrt_input_name name,int64_t at,struct xrt_space_relation *out) {
    (void)at;memset(out,0,sizeof(*out));out->pose.orientation.w=1;
    struct pico_controller *c=(struct pico_controller*)device;int index;
    if(name==XRT_INPUT_TOUCH_GRIP_POSE)index=1+c->hand;
    else if(name==XRT_INPUT_TOUCH_AIM_POSE)index=3+c->hand;
    else return XRT_ERROR_INPUT_UNSUPPORTED;
    struct ouxd_snapshot frame;if(!controller_frame(c,&frame))return XRT_SUCCESS;
    uint32_t flags=controller_pose_flags(&frame,index);float p[13];memcpy(p,frame.tracking+40+index*64,sizeof(p));
    if(flags&1) {out->pose.orientation=(struct xrt_quat){p[3],p[4],p[5],p[6]};out->relation_flags|=XRT_SPACE_RELATION_ORIENTATION_VALID_BIT;}
    if(flags&2) {out->pose.position=(struct xrt_vec3){p[0],p[1],p[2]};out->relation_flags|=XRT_SPACE_RELATION_POSITION_VALID_BIT;}
    if((flags&17)==17)out->relation_flags|=XRT_SPACE_RELATION_ORIENTATION_TRACKED_BIT;
    if((flags&34)==34)out->relation_flags|=XRT_SPACE_RELATION_POSITION_TRACKED_BIT;
    if(flags&4) {out->linear_velocity=(struct xrt_vec3){p[7],p[8],p[9]};out->relation_flags|=XRT_SPACE_RELATION_LINEAR_VELOCITY_VALID_BIT;}
    if(flags&8) {out->angular_velocity=(struct xrt_vec3){p[10],p[11],p[12]};out->relation_flags|=XRT_SPACE_RELATION_ANGULAR_VELOCITY_VALID_BIT;}
    return XRT_SUCCESS;
}

#include "haptics_output.h"
static void controller_destroy(struct xrt_device *device) {u_device_free(device);}
static struct xrt_device *create_controller(struct pico_hmd *head,int hand) {
    struct pico_controller *c=U_DEVICE_ALLOCATE(struct pico_controller,0,9,1);if(!c)return NULL;
    c->head=head;c->hand=hand;struct xrt_device *d=&c->base;
    u_device_populate_function_pointers(d,controller_pose,controller_destroy);d->update_inputs=controller_inputs;d->set_output=controller_output;d->outputs[0].name=XRT_OUTPUT_NAME_TOUCH_HAPTIC;
    d->name=XRT_DEVICE_TOUCH_CONTROLLER;d->device_type=hand?XRT_DEVICE_TYPE_RIGHT_HAND_CONTROLLER:XRT_DEVICE_TYPE_LEFT_HAND_CONTROLLER;
    d->supported.orientation_tracking=true;d->supported.position_tracking=true;d->tracking_origin=head->base.tracking_origin;
    enum xrt_input_name names[]={XRT_INPUT_TOUCH_GRIP_POSE,XRT_INPUT_TOUCH_AIM_POSE,XRT_INPUT_TOUCH_TRIGGER_VALUE,XRT_INPUT_TOUCH_SQUEEZE_VALUE,XRT_INPUT_TOUCH_THUMBSTICK,XRT_INPUT_TOUCH_THUMBSTICK_CLICK,hand?XRT_INPUT_TOUCH_A_CLICK:XRT_INPUT_TOUCH_X_CLICK,hand?XRT_INPUT_TOUCH_B_CLICK:XRT_INPUT_TOUCH_Y_CLICK,XRT_INPUT_TOUCH_MENU_CLICK};
    for(int i=0;i<9;i++)d->inputs[i].name=names[i];
    snprintf(d->str,sizeof(d->str),"OpenUUYC PICO %s (Touch-compatible)",hand?"right":"left");snprintf(d->serial,sizeof(d->serial),"OpenUUYC-PICO-%s",hand?"R":"L");
    controller_inputs(d);return d;
}

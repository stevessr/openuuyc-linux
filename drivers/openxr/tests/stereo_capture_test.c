#include "../stereo_capture.h"
#include <assert.h>
#include <stdlib.h>
#include <string.h>
int main(void) {
    assert(oust_valid_eye_geometry(4096,4096,4096u*4u));
    assert(!oust_valid_eye_geometry(4097,4096,4097u*4u));
    assert(!oust_valid_eye_geometry(4096,4095,4096u*4u));
    assert(!oust_valid_eye_geometry(4095,4096,4095u*4u));
    assert(!oust_valid_eye_geometry(64,64,64u*4u-1u));
    unsigned char *m=calloc(1,OUST_MAP_BYTES);
    unsigned char left[64*260]={1,2,3,4,5,6,7,8};
    unsigned char right[64*64*4]={21};
    left[260]=9;left[261]=10;left[262]=11;left[263]=12;
    left[264]=13;left[265]=14;left[266]=15;left[267]=16;
    float views[22]={.032f};oust_set_views(views);
    assert(!oust_copy_eye(m,OUST_MAP_BYTES,1,1,42,64,64,64u*4u,right));
    assert(oust_copy_eye(m,OUST_MAP_BYTES,0,1,42,64,64,64u*4u+4u,left));
    assert(*(uint64_t*)m&1);
    assert(!oust_copy_eye(m,OUST_MAP_BYTES,1,2,42,64,64,64u*4u,right));
    assert(oust_copy_eye(m,OUST_MAP_BYTES,1,1,42,64,64,64u*4u,right));
    assert(!(*(uint64_t*)m&1));
    assert(!memcmp(m+8,"OUST",4));
    /* No head pose set: the owned Monado producer stays version 2. */
    assert(*(uint32_t*)(m+12)==2 && m[136]==0);
    assert(!memcmp(m+48,views,sizeof(views)));
    assert(m[256]==1 && m[256+256]==9 && m[256+OUST_EYE_BYTES]==21);
    /* An external producer sets the rendering head pose; the same pair then
     * exports version 3 with the pose at 136, never a silent v2 downgrade. */
    float head[7]={1,-2,.5f,.5f,.5f,.5f,.5f};oust_set_head(head);
    assert(oust_copy_eye(m,OUST_MAP_BYTES,0,3,43,64,64,64u*4u+4u,left));
    assert(oust_copy_eye(m,OUST_MAP_BYTES,1,3,43,64,64,64u*4u,right));
    assert(*(uint32_t*)(m+12)==3 && !memcmp(m+136,head,sizeof(head)));
    assert(*(uint64_t*)(m+16)==3 && *(uint64_t*)(m+24)==43);
    assert(!oust_copy_eye(m,OUST_MAP_BYTES,0,2,42,4097,2,4097u*4u,left));
    free(m);
}

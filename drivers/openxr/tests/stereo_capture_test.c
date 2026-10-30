#include "../stereo_capture.h"
#include <assert.h>
#include <stdlib.h>
#include <string.h>
int main(void) {
    unsigned char *m=calloc(1,OUST_MAP_BYTES);
    unsigned char left[24]={1,2,3,4,5,6,7,8,99,99,99,99,9,10,11,12,13,14,15,16};
    unsigned char right[16]={21};
    float views[22]={.032f};oust_set_views(views);
    assert(!oust_copy_eye(m,OUST_MAP_BYTES,1,1,42,2,2,8,right));
    assert(oust_copy_eye(m,OUST_MAP_BYTES,0,1,42,2,2,12,left));
    assert(*(uint64_t*)m&1);
    assert(!oust_copy_eye(m,OUST_MAP_BYTES,1,2,42,2,2,8,right));
    assert(oust_copy_eye(m,OUST_MAP_BYTES,1,1,42,2,2,8,right));
    assert(!(*(uint64_t*)m&1));
    assert(!memcmp(m+8,"OUST",4));
    assert(!memcmp(m+48,views,sizeof(views)));
    assert(m[256]==1 && m[264]==9 && m[256+OUST_EYE_BYTES]==21);
    assert(!oust_copy_eye(m,OUST_MAP_BYTES,0,2,42,1025,2,5000,left));
    free(m);
}

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "stereo_capture.h"
#include <string.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/file.h>
#include <fcntl.h>
#include <unistd.h>

static uint64_t pending_id,pending_timestamp;
static uint32_t pending_width,pending_height;
static int pending_left;
static float render_views[22];
static uint32_t render_origin;
static float render_head[7];
static int render_head_valid;
void oust_set_origin(uint32_t origin) {render_origin=origin;}
void oust_set_views(const float views[22]) {memcpy(render_views,views,sizeof(render_views));}
void oust_set_head(const float head[7]) {memcpy(render_head,head,sizeof(render_head));render_head_valid=1;}
int oust_copy_eye(unsigned char *m,size_t size,uint32_t eye,uint64_t id,uint64_t ts,
                  uint32_t w,uint32_t h,uint32_t stride,const unsigned char *pixels)
{
    if(!m || ((uintptr_t)m&7) || size!=OUST_MAP_BYTES || !pixels || eye>1 || !id || !ts ||
       !w || !h || w>OUST_WIDTH || h>OUST_HEIGHT || stride<w*4) return 0;
    uint64_t *seq=(uint64_t*)m;
    if(!eye) {
        uint64_t s=__atomic_load_n(seq,__ATOMIC_RELAXED);
        __atomic_store_n(seq,(s|1),__ATOMIC_SEQ_CST);
        pending_id=id;pending_timestamp=ts;pending_width=w;pending_height=h;pending_left=1;
    } else if(!pending_left || pending_id!=id || pending_timestamp!=ts ||
              pending_width!=w || pending_height!=h) {return 0;}
    unsigned char *out=m+256+eye*OUST_EYE_BYTES;
    for(uint32_t y=0;y<h;y++) memcpy(out+y*w*4,pixels+y*stride,w*4);
    if(eye) {
        uint32_t version=render_head_valid?3:2,format=1;
        memcpy(m+8,"OUST",4);memcpy(m+12,&version,4);memcpy(m+16,&id,8);memcpy(m+24,&ts,8);
        memcpy(m+44,&render_origin,4);memcpy(m+48,render_views,sizeof(render_views));
        if(render_head_valid) memcpy(m+136,render_head,sizeof(render_head));
        memcpy(m+32,&w,4);memcpy(m+36,&h,4);memcpy(m+40,&format,4);
        uint64_t s=__atomic_load_n(seq,__ATOMIC_RELAXED);
        __atomic_store_n(seq,(s|1)+1,__ATOMIC_RELEASE);
        pending_left=0;
    }
    return 1;
}
void oust_capture_eye(uint32_t eye,uint64_t id,uint64_t ts,uint32_t w,uint32_t h,
                      uint32_t stride,const unsigned char *rgba)
{
    /* The host must create this map first. Never create a file named by an
     * untrusted application or export pixels without explicit configuration. */
    const char *path=getenv("OPENUUYC_STEREO_CAPTURE");
    if(!path || path[0]!='/') return;
    int fd=open(path,O_RDWR|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0) return;
    struct stat st;
    if(fstat(fd,&st) || !S_ISREG(st.st_mode) || st.st_uid!=getuid() ||
       (st.st_mode&077)!=0 || st.st_size!=OUST_MAP_BYTES) {close(fd);return;}
    /* One compositor process owns a producer inode for its lifetime. A new
     * host session creates a new inode, so a stale game cannot retain its lock. */
    static int producer_fd=-1;
    static dev_t producer_dev;
    static ino_t producer_ino;
    if(producer_fd<0 || producer_dev!=st.st_dev || producer_ino!=st.st_ino) {
        if(producer_fd>=0) close(producer_fd);
        producer_fd=-1;pending_left=0;
        if(flock(fd,LOCK_EX|LOCK_NB)) {close(fd);return;}
        producer_fd=fd;producer_dev=st.st_dev;producer_ino=st.st_ino;
    } else {close(fd);}
    unsigned char *map=mmap(NULL,OUST_MAP_BYTES,PROT_READ|PROT_WRITE,MAP_SHARED,producer_fd,0);
    if(map==MAP_FAILED) return;
    oust_copy_eye(map,OUST_MAP_BYTES,eye,id,ts,w,h,stride,rgba);
    munmap(map,OUST_MAP_BYTES);
}

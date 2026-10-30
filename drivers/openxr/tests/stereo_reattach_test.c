#define _GNU_SOURCE
#include "../stereo_capture.h"
#include <assert.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <string.h>
static int create(const char *path) {
    int fd=open(path,O_RDWR|O_CREAT|O_EXCL,0600);assert(fd>=0);
    assert(!ftruncate(fd,OUST_MAP_BYTES));return fd;
}
int main(void) {
    char directory[]="/tmp/oust-reattach-XXXXXX";assert(mkdtemp(directory));
    char path[256];strcpy(path,directory);strcat(path,"/stereo.shm");
    int first=create(path);assert(!setenv("OPENUUYC_STEREO_CAPTURE",path,1));
    unsigned char left[16]={11},right[16]={22};float views[22]={0};
    oust_set_views(views);
    oust_capture_eye(0,1,100,2,2,8,left);oust_capture_eye(1,1,100,2,2,8,right);
    unsigned char *old=mmap(NULL,OUST_MAP_BYTES,PROT_READ,MAP_SHARED,first,0);assert(old!=MAP_FAILED);
    assert(old[256]==11 && old[256+OUST_EYE_BYTES]==22);
    assert(!unlink(path));int second=create(path);
    left[0]=33;right[0]=44;
    oust_capture_eye(0,2,200,2,2,8,left);oust_capture_eye(1,2,200,2,2,8,right);
    unsigned char *current=mmap(NULL,OUST_MAP_BYTES,PROT_READ,MAP_SHARED,second,0);assert(current!=MAP_FAILED);
    assert(current[256]==33 && current[256+OUST_EYE_BYTES]==44);
    assert(old[256]==11 && old[256+OUST_EYE_BYTES]==22);
    assert(!(*(const uint64_t*)current&1));
    munmap(current,OUST_MAP_BYTES);munmap(old,OUST_MAP_BYTES);close(first);close(second);
    unlink(path);rmdir(directory);
}

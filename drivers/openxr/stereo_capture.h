#pragma once
#include <stdint.h>
#include <stddef.h>
#define OUST_WIDTH 1024u
#define OUST_HEIGHT 1024u
#define OUST_EYE_BYTES (OUST_WIDTH*OUST_HEIGHT*4u)
#define OUST_MAP_BYTES (256u+2u*OUST_EYE_BYTES)
/* Private, ephemeral latest-frame map supplied by the host, not recording.
 * 0: seqlock u64, 8: magic OUST, 12: version u32,
 * 16: frame id u64, 24: predicted timestamp u64,
 * 32: eye width u32, 36: height u32, 40: RGBA format=1 u32,
 * 44: origin (0 owned Monado LOCAL, 1 external compositor; alignment required).
 * 48: rendering views, 22 floats (xyz, xyzw, FOV left/right/up/down per eye).
 * Pixels: tightly packed left eye at 256, right at 256+OUST_EYE_BYTES. */
int oust_copy_eye(unsigned char *map, size_t size, uint32_t eye,
                  uint64_t id,uint64_t timestamp,uint32_t width,uint32_t height,
                  uint32_t stride,const unsigned char *rgba);
void oust_capture_eye(uint32_t eye,uint64_t id,uint64_t timestamp,
                      uint32_t width,uint32_t height,uint32_t stride,const unsigned char *rgba);

void oust_set_views(const float views[22]);

/* 0 = owned Monado LOCAL; 1 = external compositor origin, alignment required. */
void oust_set_origin(uint32_t origin);

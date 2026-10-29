// OpenUUYC local device snapshot ABI. All numeric fields are little-endian.
#pragma once
#include <stdint.h>
#include <stddef.h>
#define OUXD_MAP_BYTES 4096
struct ouxd_snapshot {
    uint8_t magic[4];
    uint32_t version;
    uint64_t guard;
    uint64_t generation;
    int64_t received_ns;
    uint32_t eye_width,eye_height;
    float refresh_hz;
    uint32_t reserved;
    float views[22]; // per eye: xyz, xyzw, FOV left/right/up/down
    uint8_t tracking[224];
};
_Static_assert(sizeof(struct ouxd_snapshot)==360,"device snapshot ABI size");
_Static_assert(offsetof(struct ouxd_snapshot,tracking)==136,"tracking offset");
// Bounded seqlock read. Publisher is the authenticated host, readers never write.
static inline int ouxd_copy(const volatile struct ouxd_snapshot *source,struct ouxd_snapshot *dest) {
    for(int attempt=0;attempt<8;++attempt) {
        uint64_t before=__atomic_load_n(&source->guard,__ATOMIC_ACQUIRE);
        if(before&1) continue;
        for(size_t i=0;i<sizeof(*dest);++i) ((uint8_t*)dest)[i]=((const volatile uint8_t*)source)[i];
        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        uint64_t after=__atomic_load_n(&source->guard,__ATOMIC_ACQUIRE);
        if(before==after && !(after&1)) return 1;
    }
    return 0;
}

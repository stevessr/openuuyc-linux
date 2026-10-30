"""Pinned-source overlay: real left/right compositor scratch readback, opt-in."""
from pathlib import Path
import shutil
HERE=Path(__file__).resolve().parent
def replace(path, old, new):
    value=path.read_text()
    if value.count(old)!=1: raise RuntimeError(f"stereo hook ABI changed: {path}: {old[:70]}")
    path.write_text(value.replace(old,new,1))
def apply(root):
    main=Path(root)/"src/xrt/compositor/main"
    for name in ("stereo_capture.c","stereo_capture.h"): shutil.copy2(HERE/name,main/name)
    with (main/"CMakeLists.txt").open("a") as f: f.write('\ntarget_sources(comp_main PRIVATE stereo_capture.c)\n')
    header=main/"comp_mirror_to_debug_gui.h"
    replace(header,"struct comp_mirror_to_debug_gui\n{","struct comp_mirror_to_debug_gui\n{\n bool oust_enabled;\n uint32_t oust_eye;")
    source=main/"comp_mirror_to_debug_gui.c"
    replace(source,'#include "xrt/xrt_results.h"','#include "xrt/xrt_results.h"\n#include "stereo_capture.h"\n#include <stdlib.h>')
    replace(source,"double target_height = 1080;","double target_height = getenv(\"OPENUUYC_STEREO_CAPTURE\") ? 512 : 1080;")
    replace(source,"u_sink_debug_push_frame(&m->debug_sink, frame);",
        """if (m->oust_enabled) {
        oust_capture_eye(m->oust_eye, frame_id, predicted_display_time_ns,
                         frame->width, frame->height, frame->stride, frame->data);
    } else { u_sink_debug_push_frame(&m->debug_sink, frame); }""")
    renderer=main/"comp_renderer.c"
    replace(renderer,"struct comp_mirror_to_debug_gui mirror_to_debug_gui;",
        "struct comp_mirror_to_debug_gui mirror_to_debug_gui;\n struct comp_mirror_to_debug_gui oust[2];\n bool oust_initialized;")
    replace(renderer,"static void\nrenderer_fini(struct comp_renderer *r)",
        "static void\nrenderer_fini(struct comp_renderer *r)")
    replace(renderer,"comp_mirror_fini(&r->mirror_to_debug_gui, vk);",
        "comp_mirror_fini(&r->mirror_to_debug_gui, vk);\n if(r->oust_initialized) for(int i=0;i<2;i++) comp_mirror_fini(&r->oust[i],vk);")
    # Initialize lazily after a real frame exists; skip capture on failure.
    marker="\tbool present_success = renderer_present_swapchain_image"
    capture="""    bool oust_has_projection=false;
    for(uint32_t i=0;i<c->base.layer_accum.layer_count;i++) {
        enum xrt_layer_type type=c->base.layer_accum.layers[i].data.type;
        if(type==XRT_LAYER_PROJECTION || type==XRT_LAYER_PROJECTION_DEPTH)oust_has_projection=true;
    }
    if (getenv("OPENUUYC_STEREO_CAPTURE") && view_count == 2 && oust_has_projection) {
        if (!r->oust_initialized) {
            for (uint32_t eye=0;eye<2;eye++) {
                const struct comp_scratch_single_images *v=&c->scratch.views[eye].cssi;
                VkExtent2D extent={v->info.width,v->info.height};
                VkResult result=comp_mirror_init(&r->oust[eye],&c->base.vk,&c->shaders,extent);
                if(result!=VK_SUCCESS) {
                    for(uint32_t j=0;j<=eye;j++) comp_mirror_fini(&r->oust[j],&c->base.vk);
                    goto oust_done;
                }
                r->oust[eye].oust_enabled=true;r->oust[eye].oust_eye=eye;
            }
            r->oust_initialized=true;
        }
        float views[22];
        for(uint32_t eye=0;eye<2;eye++) {
            const struct xrt_pose *pose=&c->base.frame_params.poses[eye];
            const struct xrt_fov *fov=&c->base.frame_params.fovs[eye];
            const float values[11]={pose->position.x,pose->position.y,pose->position.z,
                pose->orientation.x,pose->orientation.y,pose->orientation.z,pose->orientation.w,
                fov->angle_left,fov->angle_right,fov->angle_up,fov->angle_down};
            memcpy(views+eye*11,values,sizeof(values));
        }
        oust_set_views(views);
        for(uint32_t eye=0;eye<2;eye++) {
            const uint32_t index=frame_state.scratch_state.views[eye].index;
            struct comp_scratch_single_images *v=&c->scratch.views[eye].cssi;
            struct render_scratch_color_image *image=&v->images[index];
            VkExtent2D extent={v->info.width,v->info.height};
            struct xrt_normalized_rect rect={0,0,1,1};
            if(comp_mirror_do_blit(&r->oust[eye],&c->base.vk,c->frame.rendering.id,
               c->frame.rendering.predicted_display_time_ns,image->image,image->srgb_view,
               c->nr.samplers.clamp_to_edge,extent,rect)!=XRT_SUCCESS) break;
        }
    }
oust_done:
"""
    replace(renderer,marker,capture+marker)
    replace(renderer,'#include "main/comp_mirror_to_debug_gui.h"','#include "main/comp_mirror_to_debug_gui.h"\n#include <stdlib.h>\n#include "stereo_capture.h"')
    compositor=main/"comp_compositor.c"
    replace(compositor,"!c->peek &&","!c->peek && !getenv(\"OPENUUYC_STEREO_CAPTURE\") &&")
    # getenv has an explicit declaration even if upstream's transitive includes change.
    compositor.write_text('#include <stdlib.h>\n'+compositor.read_text())
    # GL opaque-FD import is not cross-GPU. Reject mismatched GLX contexts before
    # touching driver memory-import paths (Mesa can segfault on NVIDIA handles).
    glx=Path(root)/"src/xrt/state_trackers/oxr/oxr_session_gfx_gl_xlib.c"
    replace(glx,'#include <stdlib.h>', '#include <stdlib.h>\n#include <string.h>\n#include "ogl/ogl_api.h"\n#include "client/comp_gl_client.h"')
    replace(glx,"\tsess->compositor = xc;","""    if (client_gl_compositor_context_begin(xc, CLIENT_GL_CONTEXT_REASON_OTHER) != XRT_SUCCESS) {
        xrt_comp_destroy(&xc);
        return oxr_error(log, XR_ERROR_GRAPHICS_DEVICE_INVALID, "Cannot inspect OpenGL GPU identity");
    }
    bool matched = false;
    if (GLAD_GL_EXT_memory_object && glGetUnsignedBytei_vEXT) {
        GLint count = 0;
        glGetIntegerv(GL_NUM_DEVICE_UUIDS_EXT, &count);
        for (GLint i = 0; i < count && i < 16; i++) {
            GLubyte uuid[GL_UUID_SIZE_EXT] = {0};
            glGetUnsignedBytei_vEXT(GL_DEVICE_UUID_EXT, i, uuid);
            if (memcmp(uuid, sys->xsysc->info.compositor_vk_deviceUUID.data, XRT_UUID_SIZE) == 0) matched = true;
        }
    }
    client_gl_compositor_context_end(xc, CLIENT_GL_CONTEXT_REASON_OTHER);
    if (!matched) {
        xrt_comp_destroy(&xc);
        return oxr_error(log, XR_ERROR_GRAPHICS_DEVICE_INVALID,
            "OpenGL GPU does not match compositor GPU; select the same GPU for application and runtime");
    }
    sess->compositor = xc;""")
if __name__=="__main__":
    import sys
    apply(Path(sys.argv[1]))

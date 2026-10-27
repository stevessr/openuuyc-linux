#include "xr_pointer_feedback.h"
#include <cassert>
int main() {
    for(int i=0;i<6;++i) {assert(xr_stream_content(true,i));assert(!xr_stream_content(false,i));}
    assert(!xr_stream_content(true,6)); // main menu is NOT a seventh desktop
    assert(!xr_stream_content(true,43));
    for(int i=0;i<6;++i) {assert(xr_control_owner(43+i)==i);assert(xr_control_owner(49+i)==i);assert(xr_control_owner(55+i)==i);assert(xr_control_owner(61+i)==i);}
    assert(xr_control_owner(6)==-1);
    XrPointerIntent h[2]={{true,true,true,false,false},{true,true,false,false,false}};
    assert(xr_choose_pointer(h,-1,1)==0); // left menu hover beats idle right desktop
    h[0].pressed=true;h[0].rising=true;
    assert(xr_choose_pointer(h,-1,1)==0); // left menu click never starved
    assert(xr_choose_pointer(h,1,1)==1); // captured right drag cannot be stolen
    h[0]={};assert(xr_choose_pointer(h,-1,0)==1);
    h[1]={};assert(xr_choose_pointer(h,-1,1)==-1);
}

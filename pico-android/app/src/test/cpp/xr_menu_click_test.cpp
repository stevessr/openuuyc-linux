#include "xr_menu_click.h"
#include <cassert>
int main() {
    assert(xr_menu_click(6,true,true));
    assert(!xr_menu_click(6,true,false)); // held across space/target changes
    assert(!xr_menu_click(6,false,true)); // release
    assert(!xr_menu_click(0,true,true)); // remote desktop, not menu
    assert(!xr_menu_click(-1,true,true)); // ray miss
}

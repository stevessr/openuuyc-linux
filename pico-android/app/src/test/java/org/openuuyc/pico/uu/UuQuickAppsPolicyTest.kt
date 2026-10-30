package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
class UuQuickAppsPolicyTest {
    @Test fun browsingDoesNotRequireShellExecution() {
        assertTrue(UuQuickAppsPolicy.permitted("list",true,false))
        assertTrue(UuQuickAppsPolicy.permitted("vr-runtime",true,false))
        assertTrue(UuQuickAppsPolicy.permitted("vr-applications",true,false))
        for(op in listOf("launch","vr-runtime-set","unknown"))
            assertFalse(UuQuickAppsPolicy.permitted(op,true,false))
    }
    @Test fun revokedCatalogCannotBeRead() {
        assertFalse(UuQuickAppsPolicy.permitted("list",false,false))
        assertFalse(UuQuickAppsPolicy.permitted("vr-runtime",false,true))
    }
}

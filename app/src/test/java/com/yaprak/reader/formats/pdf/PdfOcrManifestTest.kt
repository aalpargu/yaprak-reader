package com.yaprak.reader.formats.pdf

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfOcrManifestTest {
    @Test fun bundledModelSdkComponentsArePrivateAndConnectivityPermissionIsAvailableForItsTransport() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getPackageInfo(app.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
        val permissions = requireNotNull(info.requestedPermissions)
        assertTrue(permissions.contains(Manifest.permission.INTERNET))
        assertTrue(permissions.contains(Manifest.permission.ACCESS_NETWORK_STATE))
        assertFalse(permissions.contains(Manifest.permission.CAMERA))
        assertFalse(permissions.contains(Manifest.permission.READ_EXTERNAL_STORAGE))
        assertFalse(permissions.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE))
        val provider = requireNotNull(info.providers).single { it.name == "com.google.mlkit.common.internal.MlKitInitProvider" }
        assertFalse(provider.exported); assertEquals("${app.packageName}.mlkitinitprovider", provider.authority)
        val services = requireNotNull(info.services).filter { it.name.startsWith("com.google.mlkit.") || it.name.startsWith("com.google.android.datatransport.") }
        assertEquals(3, services.size); assertTrue(services.none { it.exported })
        val receiver = requireNotNull(info.receivers).single { it.name == "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver" }
        assertFalse(receiver.exported)
    }
}

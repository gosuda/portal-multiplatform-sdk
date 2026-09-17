package org.gosuda.portal.sample

import android.app.Application
import org.gosuda.portal.lifecycle.PortalClientHolder

/**
 * Application entry point. Initializes the process-wide [PortalClientHolder]
 * so tunnels survive Activity recreation and (with the keep-alive service)
 * backgrounding.
 */
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PortalClientHolder.init()
    }
}

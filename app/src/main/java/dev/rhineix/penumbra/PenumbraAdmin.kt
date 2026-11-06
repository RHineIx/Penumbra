package dev.rhineix.penumbra

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

class PenumbraAdmin : DeviceAdminReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Preferences(context)
            runCatching { ProfileEngine(context).repairBridgeIfOwner() }
                .onFailure { Preferences(context).log("Bridge repair: ${it.message}") }
        } else super.onReceive(context, intent)
    }
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        runCatching { ProfileEngine(context).finishProvisioning() }
            .onFailure { Preferences(context).log("Provisioning: ${it.javaClass.simpleName}: ${it.message}") }
    }
}

class ProvisioningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> {
                if(android.os.Build.VERSION.SDK_INT < 31) { setResult(RESULT_CANCELED);finish();return }
                val allowed = intent.getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES)
                if (allowed == null || DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE in allowed) {
                    setResult(RESULT_OK, Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE))
                } else setResult(RESULT_CANCELED)
                finish()
            }
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> {
                runCatching { ProfileEngine(this).finishProvisioning() }
                    .onSuccess {
                        setResult(RESULT_OK)
                        startActivity(Intent(this, MainActivity::class.java))
                    }.onFailure {
                        Preferences(this).log("Compliance failed: ${it.message}")
                        setResult(RESULT_CANCELED)
                    }
                finish()
            }
            else -> { setResult(RESULT_CANCELED); finish() }
        }
    }
}

package gov.wa.wsdot.android.wsdot.service.helpers

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import gov.wa.wsdot.android.wsdot.util.Utils

class MyNotificationWorker(appContext: Context, workerParams: WorkerParameters)
    : Worker(appContext, workerParams) {

    private val workerData = workerParams.inputData

    override fun doWork(): Result {

        try {

            val alertId = workerData.getInt("push_alert_id", 0)
            if (alertId != 0) {
                val settings = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                val receivedAlerts = Utils.loadOrderedIntList("KEY_RECEIVED_ALERTS", settings)
                receivedAlerts.add(alertId)

                Utils.saveOrderedIntList(
                    receivedAlerts,
                    "KEY_RECEIVED_ALERTS",
                    settings
                )
            }
            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "error with notification worker")
            return Result.failure()
        }
    }

    companion object {
        private val TAG = MyNotificationWorker::class.java.simpleName
    }
}
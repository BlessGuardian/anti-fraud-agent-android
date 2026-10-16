package com.example.antifraudagent.data.guardian

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** No celular do responsavel: consulta periodicamente os alertas das pessoas vinculadas. */
class GuardianWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        GuardianRepository.getInstance(applicationContext).checkForNewAlerts()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "guardian_alert_check"

        /** 15 min e o menor intervalo periodico que o Android permite. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GuardianWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

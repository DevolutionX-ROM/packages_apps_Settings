/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.datausage.lib

import android.content.Context
import android.net.NetworkPolicy
import android.net.NetworkPolicyManager
import android.net.NetworkTemplate
import android.text.format.DateUtils
import android.util.Range
import com.android.settingslib.NetworkPolicyEditor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

interface INetworkCycleDataRepository {
    suspend fun loadCycles(): List<NetworkUsageData>
    fun getCycles(): List<Range<Long>>
    fun getPolicy(): NetworkPolicy?
    suspend fun querySummary(startTime: Long, endTime: Long): NetworkCycleChartData?
}

class NetworkCycleDataRepository(
    context: Context,
    private val networkTemplate: NetworkTemplate,
    private val networkStatsRepository: NetworkStatsRepository =
        NetworkStatsRepository(context, networkTemplate),
) : INetworkCycleDataRepository {

    private val policyManager = context.getSystemService(NetworkPolicyManager::class.java)!!

    override suspend fun loadCycles(): List<NetworkUsageData> =
        getCycles().queryUsage().filter { it.usage > 0 }

    override fun getCycles(): List<Range<Long>> {
        val policy = getPolicy() ?: return queryCyclesAsFourWeeks()
        return policy.cycleIterator().asSequence().map {
            Range(it.lower.toInstant().toEpochMilli(), it.upper.toInstant().toEpochMilli())
        }.toList()
    }

    private fun queryCyclesAsFourWeeks(): List<Range<Long>> {
        val timeRange = networkStatsRepository.getTimeRange() ?: return emptyList()
        return reverseBucketRange(
            startTime = timeRange.lower,
            endTime = timeRange.upper,
            bucketSize = DateUtils.WEEK_IN_MILLIS * 4,
        )
    }

    override fun getPolicy(): NetworkPolicy? =
        with(NetworkPolicyEditor(policyManager)) {
            read()
            getPolicy(networkTemplate)
        }

    override suspend fun querySummary(startTime: Long, endTime: Long): NetworkCycleChartData? {
        val usage = networkStatsRepository.querySummaryForDevice(startTime, endTime)
        if (usage > 0L) {
            return NetworkCycleChartData(
                total = NetworkUsageData(startTime, endTime, usage),
                dailyUsage = bucketRange(
                    startTime = startTime,
                    endTime = endTime,
                    bucketSize = NetworkCycleChartData.BUCKET_DURATION.inWholeMilliseconds,
                ).queryUsage(),
            )
        }
        return null
    }

    private suspend fun List<Range<Long>>.queryUsage(): List<NetworkUsageData> = coroutineScope {
        map { range ->
            async {
                NetworkUsageData(
                    startTime = range.lower,
                    endTime = range.upper,
                    usage = networkStatsRepository.querySummaryForDevice(range.lower, range.upper),
                )
            }
        }.awaitAll()
    }

    private fun bucketRange(startTime: Long, endTime: Long, bucketSize: Long): List<Range<Long>> {
        val buckets = mutableListOf<Range<Long>>()
        var currentStart = startTime
        while (currentStart < endTime) {
            val bucketEnd = currentStart + bucketSize
            buckets += Range(currentStart, bucketEnd)
            currentStart = bucketEnd
        }
        return buckets
    }

    private fun reverseBucketRange(
        startTime: Long,
        endTime: Long,
        bucketSize: Long,
    ): List<Range<Long>> {
        val buckets = mutableListOf<Range<Long>>()
        var currentEnd = endTime
        while (currentEnd > startTime) {
            val bucketStart = currentEnd - bucketSize
            buckets += Range(bucketStart, currentEnd)
            currentEnd = bucketStart
        }
        return buckets
    }
}

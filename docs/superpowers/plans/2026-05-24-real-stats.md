# Real Sync Statistics

> **For agentic workers:** Execute task-by-task.

**Goal:** Replace hardcoded stats with real DB-aggregated data.

**Architecture:** Backend `/member/stats` queries `music_sync_record` for current user's account. Frontend `/app/history` reads the response, falls back to demo data.

---

### Task 1: Backend — real stats computation

**Files:** Modify `MemberSyncController.java`

Replace the `stats()` method to delegate to a new service method that computes real stats:

```java
@ApiOperation("获取当前用户同步统计")
@GetMapping("/stats")
public CommonResult<Map<String, Object>> stats() {
    return CommonResult.success(spotifySyncService.computeStats());
}
```

Add to `SpotifySyncService.java`:
```java
Map<String, Object> computeStats();
```

Add to `SpotifySyncServiceImpl.java` (after backfillCovers):
```java
@Override
public Map<String, Object> computeStats() {
    SpotifySyncAccount account = getAccount();
    Map<String, Object> data = new LinkedHashMap<>();
    if (account == null) {
        data.put("totalSynced", 0);
        data.put("weeklyNew", 0);
        data.put("successRate", "0%");
        data.put("consecutiveDays", 0);
        data.put("dailyCounts", Collections.emptyList());
        data.put("statusDistribution", Collections.emptyMap());
        return data;
    }
    String accountKey = account.getAccountKey();

    // Total synced
    QueryWrapper<TrackSyncRecord> totalW = new QueryWrapper<>();
    totalW.lambda().eq(TrackSyncRecord::getAccountKey, accountKey)
            .eq(TrackSyncRecord::getSyncStatus, "SUCCESS");
    long totalSynced = trackSyncRecordMapper.selectCount(totalW);

    // Weekly new
    Calendar cal = Calendar.getInstance();
    cal.add(Calendar.DAY_OF_YEAR, -7);
    QueryWrapper<TrackSyncRecord> weekW = new QueryWrapper<>();
    weekW.lambda().eq(TrackSyncRecord::getAccountKey, accountKey)
            .eq(TrackSyncRecord::getSyncStatus, "SUCCESS")
            .ge(TrackSyncRecord::getUpdateTime, cal.getTime());
    long weeklyNew = trackSyncRecordMapper.selectCount(weekW);

    // Total all statuses for rate
    QueryWrapper<TrackSyncRecord> allW = new QueryWrapper<>();
    allW.lambda().eq(TrackSyncRecord::getAccountKey, accountKey);
    long totalAll = trackSyncRecordMapper.selectCount(allW);
    String successRate = totalAll > 0
            ? String.format("%.1f%%", (double) totalSynced / totalAll * 100)
            : "0%";

    // Success, not found, failed counts
    long notFound = countByStatus(accountKey, "NOT_FOUND");
    long failed = countByStatus(accountKey, "FAILED") + countByStatus(accountKey, "VERIFY_FAILED");

    Map<String, Long> distribution = new LinkedHashMap<>();
    distribution.put("success", totalSynced);
    distribution.put("notFound", notFound);
    distribution.put("failed", failed);

    // Daily counts for last 14 days (from spotify_added_at)
    List<Map<String, Object>> dailyCounts = new ArrayList<>();
    Calendar dayCal = Calendar.getInstance();
    for (int i = 13; i >= 0; i--) {
        dayCal.setTime(new Date());
        dayCal.add(Calendar.DAY_OF_YEAR, -i);
        dayCal.set(Calendar.HOUR_OF_DAY, 0);
        dayCal.set(Calendar.MINUTE, 0);
        dayCal.set(Calendar.SECOND, 0);
        Date dayStart = dayCal.getTime();
        dayCal.set(Calendar.HOUR_OF_DAY, 23);
        dayCal.set(Calendar.MINUTE, 59);
        dayCal.set(Calendar.SECOND, 59);
        Date dayEnd = dayCal.getTime();

        QueryWrapper<TrackSyncRecord> dayW = new QueryWrapper<>();
        dayW.lambda().eq(TrackSyncRecord::getAccountKey, accountKey)
                .eq(TrackSyncRecord::getSyncStatus, "SUCCESS")
                .ge(TrackSyncRecord::getSpotifyAddedAt, dayStart)
                .le(TrackSyncRecord::getSpotifyAddedAt, dayEnd);
        long count = trackSyncRecordMapper.selectCount(dayW);
        Map<String, Object> dayItem = new LinkedHashMap<>();
        dayItem.put("date", String.format("%02d/%02d", dayCal.get(Calendar.MONTH) + 1, dayCal.get(Calendar.DAY_OF_MONTH)));
        dayItem.put("count", count);
        dailyCounts.add(dayItem);
    }

    // Consecutive days
    int consecutiveDays = 0;
    Calendar consCal = Calendar.getInstance();
    while (true) {
        consCal.set(Calendar.HOUR_OF_DAY, 0);
        consCal.set(Calendar.MINUTE, 0);
        consCal.set(Calendar.SECOND, 0);
        Date dayStart = consCal.getTime();
        consCal.set(Calendar.HOUR_OF_DAY, 23);
        consCal.set(Calendar.MINUTE, 59);
        Date dayEnd = consCal.getTime();

        QueryWrapper<TrackSyncRecord> cw = new QueryWrapper<>();
        cw.lambda().eq(TrackSyncRecord::getAccountKey, accountKey)
                .ge(TrackSyncRecord::getUpdateTime, dayStart)
                .le(TrackSyncRecord::getUpdateTime, dayEnd);
        if (trackSyncRecordMapper.selectCount(cw) > 0) {
            consecutiveDays++;
            consCal.add(Calendar.DAY_OF_YEAR, -1);
        } else {
            break;
        }
    }

    data.put("totalSynced", totalSynced);
    data.put("weeklyNew", weeklyNew);
    data.put("successRate", successRate);
    data.put("consecutiveDays", consecutiveDays);
    data.put("dailyCounts", dailyCounts);
    data.put("statusDistribution", distribution);
    return data;
}

private long countByStatus(String accountKey, String status) {
    QueryWrapper<TrackSyncRecord> w = new QueryWrapper<>();
    w.lambda().eq(TrackSyncRecord::getAccountKey, accountKey)
            .eq(TrackSyncRecord::getSyncStatus, status);
    return trackSyncRecordMapper.selectCount(w);
}
```

Needed imports: `java.util.Calendar`.

---

### Task 2: Frontend — use real stats

**Files:** Modify `src/views/app/history.vue`

Update the script to use real API data for KPI cards and charts. The dailyCounts replace barData, statusDistribution replaces pieData.

Key changes in script:
```typescript
const stats = ref<Record<string, unknown> | null>(null)

// Compute bar data from dailyCounts
const barValues = computed(() => {
  const counts = (stats.value?.dailyCounts as Array<{count: number}>) || []
  if (counts.length > 0) return counts.map(d => d.count)
  return [5,7,9,6,11,8,12,10,8,13,10,14,9,6]
})
const barLabelsComputed = computed(() => {
  const counts = (stats.value?.dailyCounts as Array<{date: string}>) || []
  if (counts.length > 0) return counts.map(d => d.date)
  return ['一','二','三','四','五','六','日','一','二','三','四','五','六','日']
})

// Compute pie from statusDistribution
const pieComputed = computed(() => {
  const d = stats.value?.statusDistribution as Record<string, number> | undefined
  if (d && (d.success || d.notFound || d.failed)) {
    const total = (d.success||0) + (d.notFound||0) + (d.failed||0)
    return [
      { label: '成功', value: total ? Math.round((d.success||0)/total*100) : 0, color: '#22c55e' },
      { label: '未找到', value: total ? Math.round((d.notFound||0)/total*100) : 0, color: '#f59e0b' },
      { label: '失败', value: total ? Math.round((d.failed||0)/total*100) : 0, color: '#ef4444' },
    ]
  }
  return [{ label: '成功', value: 89, color: '#22c55e' },{ label: '未找到', value: 8, color: '#f59e0b' },{ label: '失败', value: 3, color: '#ef4444' }]
})

onMounted(async () => {
  loading.value = true
  try {
    const [statusRes, statsRes] = await Promise.all([
      getSyncStatusAPI(), getMemberStatsAPI()
    ])
    if (statusRes.code === 200) syncStatus.value = statusRes.data
    if (statsRes.code === 200) stats.value = statsRes.data
  } catch { /* */ } finally { loading.value = false }
})
```

Update template bindings: `stats?.totalSynced`, `stats?.weeklyNew`, `stats?.successRate`, `stats?.consecutiveDays`, `barValues`, `barLabelsComputed`, `pieComputed`.

package app.relrecord;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

// 통화가 끝난 뒤 몇 초 있다가(통화 기록이 써진 뒤) 새 통화를 읽고 보낸다.
// 15분마다: 놓친 통화 읽기·못 보낸 것 다시 보내기·지운 질문 알림 다시 띄우기·새 버전 알림(업무 기록 SyncJob과 같은 몫).
public class CallJob extends JobService {
    private static final int SOON = 1, PERIODIC = 2, RETRY = 3;

    // 통화가 끝났을 때
    static void soon(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        JobInfo.Builder b = new JobInfo.Builder(SOON, new ComponentName(c, CallJob.class))
                .setMinimumLatency(4_000)
                .setOverrideDeadline(60_000);
        try { js.schedule(b.build()); } catch (Exception ignored) {}
    }

    // 못 보낸 게 있을 때: 인터넷이 되면 다시
    static void retry(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        JobInfo.Builder b = new JobInfo.Builder(RETRY, new ComponentName(c, CallJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true);
        try { js.schedule(b.build()); } catch (Exception ignored) {}
    }

    static void schedulePeriodic(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(PERIODIC) != null) return;
        JobInfo.Builder b = new JobInfo.Builder(PERIODIC, new ComponentName(c, CallJob.class))
                .setPeriodic(15 * 60_000L)
                .setPersisted(true);
        try { js.schedule(b.build()); } catch (Exception ignored) {}
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            Context c = getApplicationContext();
            Calls.run(c);
            boolean left = Store.get(c).outbox().length() > 0;
            if (params.getJobId() == PERIODIC) {
                Calls.repost(c);
                Updater.notifyIfNew(c);
            }
            if (left && params.getJobId() != RETRY) retry(c);
            jobFinished(params, left && params.getJobId() == RETRY);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}

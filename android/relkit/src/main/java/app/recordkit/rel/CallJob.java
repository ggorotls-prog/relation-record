package app.recordkit.rel;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

// 통화가 끝난 뒤 몇 초 있다가(통화 기록이 써진 뒤) 새 통화를 읽고 보낸다.
// 15분마다: 놓친 통화 읽기·못 보낸 것 다시 보내기·지운 질문 알림 다시 띄우기·새 버전 알림(업무 기록 SyncJob과 같은 몫).
public class CallJob extends JobService {
    // 작업 번호: 관계 기록 앱은 예전 그대로 1·2·3, 라이프 RPG는 업무 기록 작업(1·2)과 안 겹치게 7401~
    private static int id(Context c, int n) { return (Rel.STANDALONE.equals(c.getPackageName()) ? 0 : 7400) + n; }
    private static final int SOON = 1, PERIODIC = 2, RETRY = 3;

    // 통화가 끝났을 때
    static void soon(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        JobInfo.Builder b = new JobInfo.Builder(id(c, SOON), new ComponentName(c, Rel.cls(c, CallJob.class)))
                .setMinimumLatency(4_000)
                .setOverrideDeadline(60_000);
        try { js.schedule(b.build()); } catch (Exception ignored) {}
    }

    // 못 보낸 게 있을 때: 인터넷이 되면 다시
    static void retry(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        JobInfo.Builder b = new JobInfo.Builder(id(c, RETRY), new ComponentName(c, Rel.cls(c, CallJob.class)))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true);
        try { js.schedule(b.build()); } catch (Exception ignored) {}
    }

    static void schedulePeriodic(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(id(c, PERIODIC)) != null) return;
        JobInfo.Builder b = new JobInfo.Builder(id(c, PERIODIC), new ComponentName(c, Rel.cls(c, CallJob.class)))
                .setPeriodic(15 * 60_000L)
                .setPersisted(true);
        try { js.schedule(b.build()); } catch (Exception ignored) {}
    }

    /** 앱마다 더 할 일(관계 기록 앱: 새 버전 알림) */
    protected void afterPeriodic(Context c) {}

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            Context c = getApplicationContext();
            Calls.run(c);
            boolean left = Store.get(c).outbox().length() > 0;
            if (params.getJobId() == id(c, PERIODIC)) {
                Calls.repost(c);
                afterPeriodic(c); // 관계 기록 앱: 새 버전이면 알림
            }
            if (left && params.getJobId() != id(c, RETRY)) retry(c);
            jobFinished(params, left && params.getJobId() == id(c, RETRY));
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}

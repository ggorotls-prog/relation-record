package app.relrecord;

// 통화 기록 읽기·보내기(통화 끝·15분·다시 보내기). 내용은 원본 한 벌 android/relkit의 CallJob(라이프 RPG와 같이 씀). 이 앱만의 것: 15분마다 새 버전 알림.
public class CallJob extends app.recordkit.rel.CallJob {
    @Override
    protected void afterPeriodic(android.content.Context c) { Updater.notifyIfNew(c); }
}

package app.relrecord;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// 알림의 [아님]: 그 번호를 제외 목록에 넣고 다시 묻지 않는다.
// 업데이트·재부팅 뒤: 15분 작업을 다시 걸고 놓친 통화를 읽는다.
public class AskReceiver extends BroadcastReceiver {
    static final String NOT_PERSON = "app.relrecord.NOT_PERSON";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (NOT_PERSON.equals(a)) {
            String phone = intent.getStringExtra("phone");
            if (phone != null && !phone.isEmpty()) Calls.notPerson(ctx, phone);
            return;
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) || Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            CallJob.schedulePeriodic(ctx);
            CallJob.soon(ctx);
        }
    }
}

package com.genymobile.scrcpy.quest;

import com.genymobile.scrcpy.util.Ln;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Independently implemented subset of the firmware's version 10 capture protocol. */
@TargetApi(34)
final class QuestCaptureService {
    private static final String DESCRIPTOR = "com.oculus.aidl.IScreenCaptureService";
    private final IBinder binder;

    interface Initializer {
        void start() throws IOException;
    }

    private static <T> T bounded(Callable<T> operation) throws IOException {
        FutureTask<T> task = new FutureTask<>(operation);
        Thread thread = new Thread(task, "quest-binder");
        thread.setDaemon(true);
        thread.start();
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("Quest capture service failed or timed out", e);
        }
    }

    QuestCaptureService() throws IOException {
        this(findService());
    }

    private static IBinder findService() throws IOException {
        return findService(() -> {
            Object manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
            Intent intent = new Intent().setComponent(new ComponentName("com.oculus.systemdriver", "com.oculus.vrapi.ScreenCaptureService"));
            return (IBinder) manager.getClass().getMethod("peekService", Intent.class, String.class, String.class)
                    .invoke(manager, intent, null, "com.android.shell");
        }, () -> QuestCommands.command("am", "startservice", "-n", "com.oculus.metacam/.capture.CaptureService"));
    }

    static IBinder findService(Callable<IBinder> lookup, Initializer initializer) throws IOException {
        IBinder service = bounded(lookup);
        if (service != null) {
            return service;
        }

        // Shell app_process cannot bind an Android service. The firmware's own
        // capture broker binds it on startup, without requesting a recording.
        Ln.i("Initializing Quest capture service");
        initializer.start();
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            service = bounded(lookup);
            if (service != null) {
                return service;
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        return null;
    }

    QuestCaptureService(IBinder binder) throws IOException {
        this.binder = binder;
        if (binder == null) {
            throw new IOException("Quest capture service is not published; start casting once from the headset or Meta Quest Developer Hub, "
                    + "stop casting, then retry");
        }
        int version = bounded(() -> {
            Parcel request = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                if (!binder.transact(1, request, reply, 0)) {
                    throw new IOException("Missing Quest capture version transaction");
                }
                reply.readException();
                return reply.readInt();
            } finally {
                request.recycle();
                reply.recycle();
            }
        });
        if (version != 10) {
            throw new IOException("Unsupported Quest capture service version: " + version + " (tested: 10)");
        }
    }

    void linkToDeath(IBinder.DeathRecipient recipient) throws IOException {
        try {
            binder.linkToDeath(recipient, 0);
        } catch (android.os.RemoteException e) {
            throw new IOException("Quest capture service died", e);
        }
    }

    private static int begin(Parcel parcel) {
        parcel.writeInt(1); // Non-null typed object
        int start = parcel.dataPosition();
        parcel.writeInt(0); // Stable AIDL parcelable size, including this word
        return start;
    }

    private static void end(Parcel parcel, int start) {
        int end = parcel.dataPosition();
        parcel.setDataPosition(start);
        parcel.writeInt(end - start);
        parcel.setDataPosition(end);
    }

    void start(Surface surface, String session, int fps) throws IOException {
        bounded(() -> {
            Parcel request = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                int start = begin(request);
                request.writeTypedObject(surface, 0);
                request.writeInt(0); // Null stabilization: firmware defaults, disabled
                int privacy = begin(request);
                request.writeInt(0); // Keep capture indicator
                request.writeInt(0); // Do not lift capture inhibit/protected content
                end(request, privacy);
                request.writeByte((byte) 0); // Left eye
                int rate = begin(request);
                request.writeByte((byte) 0); // Absolute frame rate
                request.writeInt(fps);
                end(request, rate);
                request.writeFloat(32.0f);
                request.writeString(session);
                request.writeInt(0); // Async buffer swap
                request.writeFloat(0); // Vertical offset
                request.writeInt(0); // RGB-to-YUV override
                request.writeInt(0);
                request.writeInt(0);
                request.writeInt(-1); // Null hardware buffer pool
                request.writeInt(0); // Null pipe descriptor
                end(request, start);
                if (!binder.transact(2, request, reply, 0)) {
                    throw new IOException("Unsupported Quest capture start transaction");
                }
                reply.readException();
                if (reply.readInt() != 0) {
                    int size = reply.readInt();
                    if (size < 4 || size - 4 > reply.dataAvail()) {
                        throw new IOException("Malformed Quest capture error reply");
                    }
                    throw new IOException("Quest capture: " + reply.readString());
                }
                return null;
            } finally {
                request.recycle();
                reply.recycle();
            }
        });
    }
}

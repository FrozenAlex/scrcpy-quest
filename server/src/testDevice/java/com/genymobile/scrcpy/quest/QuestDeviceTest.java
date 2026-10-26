package com.genymobile.scrcpy.quest;

import com.genymobile.scrcpy.BuildConfig;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.Workarounds;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.model.ConfigurationException;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.video.CaptureControl;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;
import com.genymobile.scrcpy.video.VideoCodec;
import com.genymobile.scrcpy.video.VideoConstraints;

import android.os.Binder;
import android.os.DeadObjectException;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.view.Surface;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Run through app_process on a connected headset; never packaged in the server. */
public final class QuestDeviceTest {
    private static final String DESCRIPTOR = "com.oculus.aidl.IScreenCaptureService";

    private QuestDeviceTest() {
    }

    private static final class Service extends Binder {
        private final int version;
        private final int error;

        Service(int version, int error) {
            this.version = version;
            this.error = error;
        }

        @Override
        protected boolean onTransact(int code, Parcel request, Parcel reply, int flags) throws RemoteException {
            request.enforceInterface(DESCRIPTOR);
            if (error == 3) {
                return false;
            }
            if (code == 2 && error == 4) {
                throw new DeadObjectException();
            }
            reply.writeNoException();
            if (code == 1) {
                reply.writeInt(version);
            } else if (code == 2) {
                reply.writeInt(error == 0 ? 0 : 1);
                if (error == 1) {
                    int start = reply.dataPosition();
                    reply.writeInt(0);
                    reply.writeString("injected capture failure");
                    reply.writeString("test stack");
                    int end = reply.dataPosition();
                    reply.setDataPosition(start);
                    reply.writeInt(end - start);
                    reply.setDataPosition(end);
                } else if (error == 2) {
                    reply.writeInt(Integer.MAX_VALUE);
                }
            } else {
                throw new AssertionError("Unexpected transaction " + code);
            }
            return true;
        }
    }

    private interface Operation {
        void run() throws IOException;
    }

    private static void expectFailure(Operation operation, String message) throws IOException {
        try {
            operation.run();
            throw new AssertionError("Expected failure: " + message);
        } catch (IOException e) {
            if (!e.toString().contains(message) && (e.getCause() == null || !e.getCause().toString().contains(message))) {
                throw new AssertionError(e);
            }
        }
    }

    private static void binderTests() throws IOException {
        expectFailure(() -> new QuestCaptureService(null), "not published");
        expectFailure(() -> new QuestCaptureService(new Service(11, 0)), "Unsupported");
        expectFailure(() -> new QuestCaptureService(new Service(10, 3)), "Missing");
        new QuestCaptureService(new Service(10, 0)).start(null, "test", 60);
        expectFailure(() -> new QuestCaptureService(new Service(10, 1)).start(null, "test", 60), "injected capture failure");
        expectFailure(() -> new QuestCaptureService(new Service(10, 2)).start(null, "test", 60), "Malformed");
        expectFailure(() -> new QuestCaptureService(new Service(10, 4)).start(null, "test", 60), "DeadObjectException");
        System.out.println("PASS: seven Binder contract/error cases");

        Service available = new Service(10, 0);
        int[] starts = {0};
        if (QuestCaptureService.findService(() -> available, () -> ++starts[0]) != available || starts[0] != 0) {
            throw new AssertionError("An available capture service must not restart the broker");
        }
        int[] lookups = {0};
        if (QuestCaptureService.findService(() -> ++lookups[0] < 4 ? null : available, () -> ++starts[0]) != available
                || starts[0] != 1) {
            throw new AssertionError("Cold startup must initialize once and wait for service publication");
        }
        expectFailure(() -> new QuestCaptureService(QuestCaptureService.findService(() -> null, () -> ++starts[0])), "not published");
        if (starts[0] != 2) {
            throw new AssertionError("An unavailable capture service must not repeatedly restart the broker");
        }
        expectFailure(() -> QuestCaptureService.findService(() -> null, () -> {
            throw new IOException("injected broker startup failure");
        }), "injected broker startup failure");
        System.out.println("PASS: four capture service initialization cases");
    }

    private static void encoderFailure(boolean noFrames) throws Exception {
        try (QuestFov reader = new QuestFov()) {
            int[] before = reader.read();
            Options options = Options.parse(new String[] {BuildConfig.VERSION_NAME, "quest_cinematic=true", "downsize_on_error=false"});
            QuestCapture quest = new QuestCapture(options);
            boolean[] started = {false};
            boolean[] released = {false};
            SurfaceCapture failingCapture = new SurfaceCapture() {
                @Override
                protected void init(VideoConstraints constraints) throws IOException, ConfigurationException {
                    quest.init(getCaptureControl(), constraints);
                }

                @Override
                public void start(Surface surface) throws IOException {
                    started[0] = true;
                    if (!noFrames) {
                        throw new IOException("Injected encoder startup failure");
                    }
                    // Deliberately leave the real MediaCodec input surface without a producer.
                }

                @Override
                public long getFrameTimeoutMs() {
                    return 500;
                }

                @Override
                public Size getSize() {
                    return quest.getSize();
                }

                @Override
                protected boolean applyNewVideoConstraints(VideoConstraints constraints) {
                    return false;
                }

                @Override
                public void release() {
                    quest.release();
                    released[0] = true;
                }
            };
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            try (ParcelFileDescriptor input = pipe[0]; ParcelFileDescriptor output = pipe[1]) {
                Streamer streamer = new Streamer(output.getFileDescriptor(), VideoCodec.H264, true, true);
                SurfaceEncoder encoder = new SurfaceEncoder(failingCapture, streamer, options);
                CountDownLatch done = new CountDownLatch(1);
                encoder.start(fatal -> done.countDown());
                if (!done.await(15, TimeUnit.SECONDS)) {
                    encoder.stop();
                    throw new AssertionError("Encoder failure did not terminate within the timeout");
                }
                encoder.join();
            }
            int[] after = reader.read();
            if (!started[0] || !released[0] || !Arrays.equals(before, after)) {
                throw new AssertionError("Encoder failure did not restore FOV: " + Arrays.toString(after));
            }
            System.out.println("PASS: " + (noFrames ? "missing frame timeout" : "encoder startup failure")
                    + " restored " + Arrays.toString(after));
        }
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Workarounds.apply();
        if (args.length == 0) {
            binderTests();
        } else if ("service".equals(args[0])) {
            new QuestCaptureService();
            System.out.println("PASS: firmware capture service available with protocol version 10");
        } else if ("encoder-failure".equals(args[0]) || "no-frames".equals(args[0])) {
            encoderFailure("no-frames".equals(args[0]));
        } else if ("read".equals(args[0])) {
            try (QuestFov reader = new QuestFov()) {
                System.out.println("FOV: " + Arrays.toString(reader.read()));
            }
        } else if ("partial-failure".equals(args[0])) {
            try (QuestFov reader = new QuestFov()) {
                int[] before = reader.read();
                QuestCapture capture = new QuestCapture(Options.parse(new String[] {BuildConfig.VERSION_NAME, "quest_cinematic=true"}));
                try {
                    capture.init(new CaptureControl(), new VideoConstraints(0, 2, null));
                    int[] cinematic = reader.read();
                    if (cinematic[0] != 57 || cinematic[1] != 57) {
                        throw new AssertionError("Preset not applied");
                    }
                    // Fail after FOV changes and before an encoder surface is attached.
                    expectFailure(() -> new QuestCaptureService(new Service(10, 1)).start(null, "test", 60), "injected capture failure");
                } finally {
                    capture.release();
                }
                int[] after = reader.read();
                if (!Arrays.equals(before, after)) {
                    throw new AssertionError("FOV not restored: " + Arrays.toString(after));
                }
                System.out.println("PASS: partial startup failure restored " + Arrays.toString(after));
            }
        } else {
            throw new IllegalArgumentException("Unknown test mode");
        }
        System.exit(0);
    }
}

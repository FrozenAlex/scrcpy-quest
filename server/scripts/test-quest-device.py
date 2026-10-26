#!/usr/bin/env python3
"""Explicit, opt-in Quest hardware tests. Build :server:assembleDebug first.

Uses only the connected headset's installed Meta casting APK. No APK is installed.
Close other casting sessions and leave a native app awake before running.
"""
import argparse
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('mode', choices=['binder', 'service', 'read', 'partial-failure', 'encoder-failure', 'no-frames', 'restart', 'kill'])
parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME'))
parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
parser.add_argument('--serial')
args = parser.parse_args()
if not args.sdk or not args.java_home:
    parser.error('Set ANDROID_HOME and JAVA_HOME, or pass --sdk and --java-home')
server_dir = Path(__file__).resolve().parents[1]
build = server_dir / 'build'
output = build / 'quest-device-tests'
output.mkdir(parents=True, exist_ok=True)
sdk = Path(args.sdk)
java_bin = Path(args.java_home) / 'bin'
adb_command = [args.adb] + (['-s', args.serial] if args.serial else [])
remote_server = '/data/local/tmp/scrcpy-quest-test.jar'
remote_tests = '/data/local/tmp/scrcpy-quest-tests.jar'
classpath = remote_server + ':' + remote_tests


def run(command, **kwargs):
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT, timeout=30, **kwargs)


def adb(*command):
    return run(adb_command + list(command))


def test(mode):
    return adb('shell', 'CLASSPATH=' + classpath + ' app_process / '
               'com.genymobile.scrcpy.quest.QuestDeviceTest' + (' ' + mode if mode else ''))


def read_fov():
    result = test('read')
    match = re.search(r'FOV: \[(\d+), (\d+), (\d+), (\d+)\]', result)
    if not match:
        raise AssertionError(result)
    return tuple(map(int, match.groups()))


def receive(sock, size):
    data = b''
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise EOFError('Video stream ended')
        data += chunk
    return data


android_jar = sdk / 'platforms/android-36/android.jar'
classes = output / 'classes'
classes.mkdir(exist_ok=True)
run([str(java_bin / 'javac'), '-source', '8', '-target', '8', '-cp',
     os.pathsep.join(map(str, [android_jar, build / 'intermediates/javac/debug/compileDebugJavaWithJavac/classes'])),
     '-d', str(classes), str(server_dir / 'src/testDevice/java/com/genymobile/scrcpy/quest/QuestDeviceTest.java')])
run([str(java_bin / 'java'), '-cp', str(sdk / 'build-tools/36.0.0/lib/d8.jar'),
     'com.android.tools.r8.D8', '--lib', str(android_jar), '--output', str(output / 'quest-tests.jar'),
     *map(str, classes.rglob('*.class'))])
adb('push', str(build / 'outputs/apk/debug/server-debug.apk'), remote_server)
adb('push', str(output / 'quest-tests.jar'), remote_tests)

try:
    if args.mode in ('binder', 'service', 'read', 'partial-failure', 'encoder-failure', 'no-frames'):
        result = test('' if args.mode == 'binder' else args.mode)
        (output / (args.mode + '.log')).write_text(result)
        print(result, flush=True)
    else:
        before = read_fov()
        print('Before:', before, flush=True)
        port = int(adb('forward', 'tcp:0', 'localabstract:scrcpy_20261017').strip())
        log_path = output / (args.mode + '.log')
        with log_path.open('w') as log:
            # Keep this test server available for independent FOV readback.
            command = ('CLASSPATH=' + remote_server + ' app_process / com.genymobile.scrcpy.Server 5.0.1 '
                       'scid=20261017 quest_cinematic=true audio=false tunnel_forward=true '
                       'send_device_meta=false max_fps=60 log_level=debug cleanup=false')
            process = subprocess.Popen(adb_command + ['shell', command], stdout=log, stderr=subprocess.STDOUT)
            video = control = None
            try:
                for _ in range(100):
                    try:
                        video = socket.create_connection(('localhost', port), timeout=1)
                        video.settimeout(20)
                        if receive(video, 1) == b'\0':
                            break
                    except (OSError, EOFError):
                        if video:
                            video.close()
                        time.sleep(.05)
                else:
                    raise AssertionError('Server did not accept connection')
                control = socket.create_connection(('localhost', port), timeout=5)
                assert receive(video, 4) == b'h264'
                frames = sessions = 0
                reset_sent = False
                while frames < 150:
                    header = receive(video, 12)
                    flags, size = struct.unpack('>QI', header)
                    if flags >> 63:
                        sessions += 1
                        width, height = struct.unpack('>II', header[4:])
                        assert (width, height) == (1920, 1080), (width, height)
                        print('Session', sessions, width, height, flush=True)
                        continue
                    receive(video, size)
                    if not flags & (1 << 62):
                        frames += 1
                    if args.mode == 'restart' and frames >= 60 and not reset_sent:
                        control.sendall(bytes([17]))  # RESET_VIDEO
                        reset_sent = True
                print('Frames:', frames, 'sessions:', sessions, flush=True)
                if args.mode == 'restart':
                    assert sessions >= 2, 'Encoder did not restart'
                else:
                    processes = adb('shell', 'ps -A -o PID,NAME,ARGS')
                    targets = [line.split()[0] for line in processes.splitlines()
                               if 'com.genymobile.scrcpy.Server' in line and 'scid=20261017' in line
                               and line.split()[1] == 'app_process']
                    assert len(targets) == 1, targets
                    adb('shell', 'kill', '-9', targets[0])
                    print('Killed owned scrcpy server PID', targets[0], flush=True)
            finally:
                if control:
                    control.close()
                if video:
                    video.close()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.terminate()
                adb('forward', '--remove', 'tcp:' + str(port))
                for _ in range(5):
                    after = read_fov()
                    if after == before:
                        break
                    time.sleep(.5)
                print('After:', after, flush=True)
                assert after == before, 'FOV was not restored'
        print(log_path.read_text(), flush=True)
finally:
    adb('shell', 'rm', '-f', remote_server, remote_tests)

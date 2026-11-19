"""Disposable, offline KevTV smoke test for a locally built debug APK (Windows SDK)."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import threading
import time
from urllib.parse import parse_qs, urlsplit
import xml.etree.ElementTree as ET

PACKAGE = "ca.kevtv"
GOOD = "demo-only-pass"
WRONG = "demo-wrong-pass"
USERNAME = "mock-viewer"
AUTH_RESULT = "Connexion refusée."
IMAGE = "system-images;android-35;google_apis_playstore;x86_64"
MOVIE = "Film Demo KevTV"
TITLE = "Ta télé, même sur la route."
CONNECT = "Connecter mon compte Xtream"


class MockXtream(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Never log URL, request headers, or credential params.

    def do_GET(self):
        url = urlsplit(self.path)
        params = parse_qs(url.query)
        action = params.get("action", [""])[0]
        allowed = params.get("username") == [USERNAME] and params.get("password") == [GOOD]
        if url.path == f"/movie/{USERNAME}/{GOOD}/100.mp4" and self.server.clip:
            data = self.server.clip
            value = self.headers.get("Range", "")
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", value) if value else None
            start = int(match[1]) if match else 0
            end = min(int(match[2]), len(data)-1) if match and match[2] else len(data)-1
            if start > end or (value and not match):
                self.send_error(416, "Plage invalide")
                return
            try:
                self.send_response(206 if match else 200)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", str(end-start+1))
                if match:
                    self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
                self.end_headers()
                self.wfile.write(data[start:end+1])
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        if url.path != "/player_api.php":
            self.send_error(404, "Route inconnue")
            return
        if not action:
            body = {"user_info": {"auth": 1 if allowed else 0, "status": "Active" if allowed else "Disabled"},
                    "server_info": {"timezone": "America/Toronto"}}
        elif not allowed:
            self.send_error(401, "Non autorise")
            return
        elif action == "get_vod_categories":
            body = [{"category_id": "10", "category_name": "Québec"}]
        elif action == "get_vod_info":
            body = {"info": {"name": MOVIE, "plot": "Video synthetique"}, "movie_data": {"stream_id": 100, "container_extension": "mp4"}}
        elif action == "get_vod_streams":
            body = [{"stream_id": 100, "category_id": "10", "name": MOVIE, "container_extension": "mp4"}]
        else:
            body = []
        payload = json.dumps(body).encode("utf-8")
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def emulator_port():
    for port in range(5570, 5586, 2):
        with socket.socket() as console, socket.socket() as client:
            try:
                console.bind(("127.0.0.1", port))
                client.bind(("127.0.0.1", port + 1))
            except OSError:
                continue
            return port
    raise RuntimeError("No free emulator port pair")


def run(command, *, env, stage, timeout=30, stdin=None, strict=True):
    try:
        result = subprocess.run(command, input=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=timeout, env=env, creationflags=subprocess.CREATE_NO_WINDOW)
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeError(stage + " timed out or failed to start") from None
    if strict and result.returncode != 0:
        raise RuntimeError(stage + f" failed (exit {result.returncode}); raw output suppressed")
    return result.stdout.decode("utf-8", "replace").strip()


def check(args):
    sdk = args.sdk.resolve()
    apk = args.apk.resolve(strict=True)
    console = emulator_port()
    serial = f"emulator-{console}"
    bridge = free_port()
    mock = ThreadingHTTPServer(("127.0.0.1", 0), MockXtream)
    mock.clip = b""
    threading.Thread(target=mock.serve_forever, daemon=True).start()
    mock_port = mock.server_port
    env = dict(os.environ)
    with tempfile.TemporaryDirectory(prefix="kevtv-emulator-") as temp:
        scratch = Path(temp)
        env.update(ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk), ANDROID_AVD_HOME=str(scratch / "avd"),
                   ANDROID_EMULATOR_HOME=str(scratch / "emulator"), ANDROID_USER_HOME=str(scratch / "user"),
                   ANDROID_ADB_SERVER_PORT=str(bridge), ANDROID_EMULATOR_ADB_SERVER_PORT=str(bridge),
                   ADB_SERVER_SOCKET=f"tcp:127.0.0.1:{bridge}")
        manager = sdk / "cmdline-tools" / "latest" / "bin" / "avdmanager.bat"
        name = "KevTV_Smoke_Fresh"
        avd = scratch / "avd" / f"{name}.avd"
        avd.parent.mkdir()
        (scratch / "emulator").mkdir()
        (scratch / "user").mkdir()
        adb_bin = str(sdk / "platform-tools" / "adb.exe")
        emu = None

        def adb(*argv, stage="isolated adb", strict=True, timeout=30, device=True):
            opts = ["-P", str(bridge)] + (["-s", serial] if device else [])
            return run([adb_bin, *opts, *argv], env=env, stage=stage, strict=strict, timeout=timeout)

        def shell(*argv, strict=True):
            return adb("shell", *argv, strict=strict)

        def nodes():
            remote = "/data/local/tmp/kevtv-smoke.xml"
            if 'dumped to' not in shell('uiautomator', 'dump', remote, strict=False):
                return []
            raw = adb("exec-out", "cat", remote, strict=False)
            try:
                return list(ET.fromstring(raw).iter("node"))
            except ET.ParseError:
                return []

        def find(label, *, absent=False, timeout=25):
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                tree = nodes()
                if tree:
                    hits = [n for n in tree if label.casefold() in (n.get("text", "").casefold(), n.get("content-desc", "").casefold())
                            or (label == AUTH_RESULT and n.get("text", "").startswith(label))]
                    if absent and not hits:
                        return None
                    if not absent and hits:
                        return hits[0]
                time.sleep(.35)
            raise RuntimeError("Expected UI state not reached: " + label)

        def tap(label):
            node = find(label)
            coords = [int(i) for i in re.findall(r"\d+", node.get("bounds", ""))]
            if len(coords) != 4:
                raise RuntimeError("Missing bounds for UI state " + label)
            x1, y1, x2, y2 = coords
            shell("input", "tap", str((x1+x2)//2), str((y1+y2)//2))

        def dismiss_ime():
            state = shell("dumpsys", "input_method", strict=False)
            if "mInputShown=true" in state or "mShowRequested=true" in state:
                shell("input", "keyevent", "KEYCODE_BACK")

        def enter(label, value, *, replace=False):
            tap(label)
            if replace:
                shell("input", "keyevent", "KEYCODE_MOVE_END")
                shell("input", "keyevent", *["KEYCODE_DEL"] * len(WRONG))
            shell("input", "text", value)
            dismiss_ime()

        try:
            run([str(manager), "create", "avd", "-n", name, "-k", IMAGE, "-p", str(avd)],
                env=env, stage="create scratch AVD", timeout=40, stdin=b"no\n")
            if not (avd / "config.ini").exists():
                raise RuntimeError("Scratch AVD not created")
            conf = avd / "config.ini"
            layout = re.sub(r"(?m)^hw\.keyboard\s*=.*\n?", "", conf.read_text(encoding="utf-8"))
            conf.write_text(layout + "\nhw.keyboard=yes\n", encoding="utf-8")
            if args.playback:
                fixture = scratch / "demo.mp4"
                ffmpeg = args.ffmpeg or Path("C:/ffmpeg/bin/ffmpeg.exe")
                run([str(ffmpeg), "-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi",
                     "-i", "testsrc2=size=160x90:rate=6", "-t", "30", "-threads", "1", "-c:v",
                     "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(fixture)],
                    env=env, stage="generate rights-free fixture")
                mock.clip = fixture.read_bytes()
            adb("start-server", device=False, stage="start isolated adb")
            emu = subprocess.Popen([str(sdk / "emulator" / "emulator.exe"), "-avd", name, "-port", str(console),
                                    "-no-window", "-no-audio", "-no-metrics", "-no-snapshot", "-no-boot-anim", "-dns-server", "127.0.0.1", "-gpu",
                                    "swiftshader_indirect", "-cores", "2", "-memory", "2048"],
                                   env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                if emu.poll() is not None:
                    raise RuntimeError(f"Headless emulator exited before boot ({emu.returncode})")
                if shell("getprop", "sys.boot_completed", strict=False) == "1":
                    break
                time.sleep(2)
            else:
                raise RuntimeError("Headless emulator did not finish booting")
            adb("reverse", f"tcp:{mock_port}", f"tcp:{mock_port}", stage="map fixture only on the isolated AVD")
            shell("svc", "wifi", "disable", strict=False)
            shell("svc", "data", "disable", strict=False)
            shell("settings", "put", "secure", "show_ime_with_hard_keyboard", "0")
            shell("wm", "size", "720x1280")
            shell("wm", "density", "160")
            shell("input", "keyevent", "KEYCODE_WAKEUP")
            shell("wm", "dismiss-keyguard", strict=False)
            adb("install", str(apk), stage="install only on fresh headless AVD", timeout=120)
            shell("am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")

            find("Connecte ton compte Xtream"); tap("Annuler")
            find(TITLE); find(CONNECT); find("Enregistrements", absent=True)
            print("PASS: fresh install starts without pairing or a recording menu.", flush=True)
            tap(CONNECT)
            assert find("Serveur (ex. https://exemple.com:443)").get("focused") == "true"
            shell("input", "text", f"http://127.0.0.1:{mock_port}")
            shell("input", "keyevent", "KEYCODE_TAB")
            assert find("Nom d'utilisateur").get("focused") == "true"
            shell("input", "text", USERNAME)
            shell("input", "keyevent", "KEYCODE_TAB")
            assert find("Mot de passe").get("focused") == "true"
            shell("input", "text", WRONG)
            dismiss_ime()
            tap("Se connecter")
            tap("Continuer en HTTP")
            find(AUTH_RESULT)
            print("PASS: mock refuses an invalid password.", flush=True)

            enter("Mot de passe", GOOD, replace=True)
            tap("Se connecter")
            tap("Continuer en HTTP")
            find(TITLE); find(CONNECT, absent=True)
            tap("Films")
            find(MOVIE)
            print("PASS: authorized mock credentials activate account and populate films.", flush=True)
            if args.playback:
                tap(MOVIE)
                tap("Regarder")
                tick = -1
                deadline = time.monotonic()+24
                while time.monotonic() < deadline:
                    # Show the control strip (no visible host window); read Media3's displayed clock.
                    tree = nodes()
                    if not any(n.get("resource-id", "").endswith(":id/exo_position") for n in tree):
                        shell("input", "tap", "500", "200")
                        tree = nodes()
                    for node in tree:
                        label = node.get("text", "")
                        if node.get("resource-id", "").endswith(":id/exo_position") and re.fullmatch(r"\d+:\d{2}", label):
                            minutes, seconds = map(int, label.split(":"))
                            point = minutes*60 + seconds
                            if tick >= 0 and point > tick and point > 0:
                                break
                            tick = point if tick < 0 else min(tick, point)
                    else:
                        continue
                    break
                else:
                    raise RuntimeError("Media3 playback clock did not progress for mock fixture")
                print("PASS: Media3 playback clock advances while decoding the generated mock MP4.", flush=True)

            shell("am", "force-stop", PACKAGE)
            shell("am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
            find(TITLE); find(CONNECT, absent=True)
            tap("Réglages")
            tap("Oublier mon compte")
            tap("Annuler")
            find("Oublier mon compte")
            tap("Oublier mon compte")
            tap("Oublier")
            find(CONNECT)
            shell("am", "force-stop", PACKAGE)
            shell("am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
            find("Connecte ton compte Xtream")
            print("PASS: account survives restart and verified forget survives restart.", flush=True)
        except (AssertionError, RuntimeError) as error:
            if args.hold_on_failure:
                print(f"QA stopped: {error}. Isolated ADB port {bridge}; serial {serial}.", flush=True)
                print(f"Create the stop flag {scratch / 'finish.flag'} when diagnostics are done. Stops automatically after 5 min.", flush=True)
                hold_deadline = time.monotonic() + 300
                while time.monotonic() < hold_deadline and not (scratch / "finish.flag").exists():
                    time.sleep(1)
            raise
        finally:
            mock.shutdown()
            mock.server_close()
            if emu is not None and emu.poll() is None:
                adb("emu", "kill", strict=False, timeout=10)
                try:
                    emu.wait(20)
                except subprocess.TimeoutExpired:
                    subprocess.run(["taskkill", "/PID", str(emu.pid), "/T", "/F"], stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW, check=False)
                    emu.wait(10)
            adb("kill-server", strict=False, device=False)
            time.sleep(1)  # Windows may take a moment to close emulator/ADB handles.


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("apk", type=Path)
    p.add_argument("--hold-on-failure", action="store_true", help="Keep only the disposable AVD for debugger for up to five minutes")
    p.add_argument("--playback", action="store_true", help="Generate an MP4 and verify the Media3 clock advances")
    p.add_argument("--ffmpeg", type=Path, help="Local ffmpeg path for --playback (default C:/ffmpeg/bin/ffmpeg.exe)")
    p.add_argument("--sdk", type=Path, default=Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk")
    try:
        check(p.parse_args())
    except (OSError, AssertionError, RuntimeError) as error:
        raise SystemExit(f"FAIL: {error}") from None
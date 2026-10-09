#!/usr/bin/env python3
"""Run MusicCam's debug-only native codec checks on an authorized USB device.

Requires the already-installed Debug APK (framework instrumentation declaration),
and existing host adb, ffmpeg, ffprobe. Runs only synthetic media, without camera,
microphone or projection permissions. Instrumentation restarts the app: stop real
recordings first. Outputs stay in an ignored .local directory.
"""
import argparse
import array
import json
import math
import shutil
import subprocess
import wave
from pathlib import Path

PACKAGE = "dev.musiccam.prototype"


def run(*args, **kwargs):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)


def json_file(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def prepare(root):
    fixtures = root / "fixtures"
    fixtures.mkdir(parents=True, exist_ok=True)
    run("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "color=c=black:s=320x240:r=30:d=8",
        "-vf", "drawbox=x=0:y=0:w=iw:h=ih:color=green:t=fill:enable='between(mod(t,1),0.5,0.69)'",
        "-c:v", "libx264", "-bf", "0", "-pix_fmt", "yuv420p", str(root / "base.mp4"))
    cases = [("aligned", 0, 8, 8, 0), ("audio_early", -.25, 3.6, 3, 90),
             ("audio_late", .25, 2.3, 3, 0), ("invalid_timing", 0, 3, 3, 0)]
    for name, delta, audio_duration, video_duration, rotation in cases:
        directory = fixtures / name
        directory.mkdir(exist_ok=True)
        run("ffmpeg", "-v", "error", "-y", "-display_rotation:v:0", str(-rotation),
            "-i", str(root / "base.mp4"), "-t", str(video_duration), "-c", "copy", str(directory / "video.mp4"))
        fixture_probe = json.loads(run("ffprobe", "-v", "error", "-show_entries", "stream_side_data",
                                      "-of", "json", str(directory / "video.mp4")).stdout)
        actual_rotation = fixture_probe["streams"][0].get("side_data_list", [{}])[0].get("rotation", 0)
        assert actual_rotation == -rotation, ("invalid fixture rotation", name, actual_rotation)
        samples = array.array("h")
        for i in range(round(audio_duration * 48000)):
            t = i / 48000
            value = round(8000 * math.sin(2 * math.pi * 1000 * t)) if .5 <= t % 1 < .7 else 0
            samples.extend((value, value))
        with wave.open(str(directory / "audio.wav"), "wb") as output:
            output.setparams((2, 2, 48000, 0, "NONE", "not compressed"))
            output.writeframes(samples.tobytes())
        json_file(directory / "session.json", {"videoOriginEstimateNs": 10_000_000_000})
        json_file(directory / "audio-timing.json", {
            "originEstimateNs": 10_000_000_000 + round(delta * 1e9),
            "originMethod": "synthetic_fixture_known_epoch", "frames": len(samples) // 2,
            "complete": name != "invalid_timing", "error": None,
            "nonZeroSamples": sum(x != 0 for x in samples), "firstNonZeroFrame": 24001,
        })
    return fixtures


def first_tone(path):
    raw = run("ffmpeg", "-v", "error", "-i", str(path), "-vn", "-ac", "1", "-ar", "48000",
              "-f", "s16le", "-").stdout
    samples = array.array("h", raw)
    # 5 ms RMS windows; measure priming, do not silently subtract it.
    for start in range(0, len(samples) - 240, 240):
        rms = math.sqrt(sum(x*x for x in samples[start:start+240]) / 240)
        if rms > 1000:
            return start / 48000
    raise AssertionError("Missing test tone")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(".local/phase3/native-checks"))
    args = parser.parse_args()
    root = args.output.resolve()
    assert ".local" in root.parts, "Synthetic files and device evidence must remain in .local"
    for name in ("adb", "ffmpeg", "ffprobe"):
        assert shutil.which(name), name + " must already be installed"
    root.mkdir(parents=True, exist_ok=True)
    fixtures = prepare(root)
    run("adb", "-d", "push", str(fixtures) + "/.", "/data/local/tmp/musiccam-native-checks")
    run("adb", "-d", "shell", "run-as", PACKAGE, "mkdir", "-p", "files/native-checks")
    # Copy individual named fixtures; never clear or touch recordings / sessions.
    for name in ("aligned", "audio_early", "audio_late", "invalid_timing"):
        run("adb", "-d", "shell", "run-as", PACKAGE, "cp", "-R",
            "/data/local/tmp/musiccam-native-checks/" + name, "files/native-checks/")
    output = run("adb", "-d", "shell", "am", "instrument", "-w",
                 PACKAGE + "/.DeviceMediaInstrumentation").stdout
    (root / "instrumentation.log").write_bytes(output)
    report = json.loads(run("adb", "-d", "exec-out", "run-as", PACKAGE,
                           "cat", "files/native-checks/report.json").stdout)
    json_file(root / "device-report.json", report)
    assert report["passed"], report
    assert any(item["fixture"] == "publication_and_failure_cleanup" and item["passed"]
               for item in report["reports"]), "Install the current Debug APK before running checks"
    results = []
    for name, expected_offset, duration, expected_rotation in (
        ("aligned", 0, 8, 0), ("audio_early", 12000, 3, -90), ("audio_late", -12000, 3, 0)):
        destination = root / (name + "-merged.mp4")
        destination.write_bytes(run("adb", "-d", "exec-out", "run-as", PACKAGE,
                                   "cat", "files/native-checks/" + name + "/merged.mp4").stdout)
        composition = json.loads(run("adb", "-d", "exec-out", "run-as", PACKAGE,
                                     "cat", "files/native-checks/" + name + "/composition.json").stdout)
        json_file(root / (name + "-composition.json"), composition)
        probe = json.loads(run("ffprobe", "-v", "error", "-show_streams", "-show_format",
                              "-of", "json", str(destination)).stdout)
        json_file(root / (name + "-ffprobe.json"), probe)
        video, audio = probe["streams"]
        assert video["codec_name"] == "h264" and audio["codec_name"] == "aac"
        assert len(probe["streams"]) == 2 and audio["sample_rate"] == "48000" and audio["channels"] == 2
        assert video["nb_frames"] == ("240" if name == "aligned" else "90")
        assert abs(float(video["duration"]) - duration) < .002
        assert abs(float(audio["duration"]) - duration) < .002
        rotation = video.get("side_data_list", [{}])[0].get("rotation", 0)
        assert rotation == expected_rotation, (name, rotation)
        assert composition["sourceOffsetFrames"] == expected_offset, composition
        if name == "audio_late":
            assert composition["leadingSilenceFrames"] == 12000
            assert composition["trailingSilenceFrames"] == 21600
        elif name == "audio_early":
            assert composition["trimmedHeadFrames"] == 12000
            assert composition["trimmedTailFrames"] == 16800
        run("ffmpeg", "-v", "error", "-xerror", "-i", str(destination),
            "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-")
        source_video = fixtures / name / "video.mp4"
        def frame_hashes(path):
            data = run("ffmpeg", "-v", "error", "-noautorotate", "-i", str(path),
                       "-map", "0:v:0", "-fps_mode", "passthrough", "-f", "framemd5", "-").stdout.decode()
            return [line.split(",")[-1].strip() for line in data.splitlines() if line and not line.startswith("#")]
        assert frame_hashes(source_video) == frame_hashes(destination), "video pixels changed during remux"
        def packet_pts(path):
            data = json.loads(run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_packets",
                                  "-show_entries", "packet=pts_time", "-of", "json", str(path)).stdout)
            return [float(packet["pts_time"]) for packet in data["packets"]]
        source_pts, output_pts = packet_pts(source_video), packet_pts(destination)
        assert len(source_pts) == len(output_pts)
        pts_error_us = max(abs((a - source_pts[0]) - b) * 1e6 for a, b in zip(source_pts, output_pts))
        assert pts_error_us <= 50, ("video PTS changed", pts_error_us)
        tone_s = first_tone(destination)
        expected_tone_s = .5 - expected_offset / 48000
        # Bound observed encoder delay for this test, not a production sync correction.
        assert -.01 <= tone_s - expected_tone_s <= .08, (name, tone_s, expected_tone_s)
        results.append({"fixture": name, "passed": True, "first_decoded_tone_s": tone_s,
                        "expected_input_tone_s": expected_tone_s,
                        "observed_aac_onset_delay_ms": (tone_s - expected_tone_s)*1000,
                        "full_decode": True, "rotation": rotation, "video_frame_hashes_match": True,
                        "max_video_pts_error_us": pts_error_us})
    result = {"passed": True, "device_framework": report, "host_validation": results}
    json_file(root / "report.json", result)
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()

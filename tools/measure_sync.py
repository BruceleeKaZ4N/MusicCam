#!/usr/bin/env python3
"""Measure green-flash / 1 kHz pulse onsets in a controlled MusicCam mirror recording.

Host-only analysis uses installed ffmpeg/ffprobe; neither is an Android dependency.
Positive offset means audio occurs later than the visible flash. This measures the
whole playback/display/capture path, not an isolated sensor or Bluetooth latency.
"""
import argparse
import array
import json
import math
import statistics
import subprocess
from pathlib import Path


def command(*args):
    return subprocess.check_output(args, stderr=subprocess.PIPE)


def onsets(times, levels, threshold, minimum_interval):
    result = []
    previous = False
    for time, level in zip(times, levels):
        on = level >= threshold
        if on and not previous and (not result or time - result[-1] >= minimum_interval):
            result.append(time)
        previous = on
    return result


def analyze(path, roi, interval=None):
    frames = json.loads(command(
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_frames",
        "-show_entries", "frame=best_effort_timestamp_time", "-of", "json", str(path)))
    times = [float(f["best_effort_timestamp_time"]) for f in frames["frames"]]
    if not times or times[-1] > 120:
        raise ValueError("Use a nonempty controlled clip of at most 120 seconds")
    width = height = 160
    pixels = command("ffmpeg", "-v", "error", "-i", str(path), "-an",
                     "-vf", "scale=160:160", "-fps_mode", "passthrough",
                     "-f", "rawvideo", "-pix_fmt", "rgb24", "-")
    stride = width * height * 3
    if len(pixels) != len(times) * stride:
        raise ValueError("Decoded frame count differs from timestamp count")
    x, y, w, h = roi
    left, top = round(x * width), round(y * height)
    right, bottom = round((x + w) * width), round((y + h) * height)
    offsets = [(row * width + col) * 3 for row in range(top, bottom) for col in range(left, right)]
    levels = []
    for start in range(0, len(pixels), stride):
        count = 0
        for pos in offsets:
            r, g, b = pixels[start + pos:start + pos + 3]
            count += g > 70 and g > r * 1.4 + 15 and g > b * 1.4 + 15
        levels.append(count / len(offsets))
    # Select only a manually verified visible interval, retaining original media PTS.
    # This does not shift either stream or infer which repeated pulse cycle is seen.
    if interval:
        selected = [(t, level) for t, level in zip(times, levels)
                    if interval[0] <= t < interval[1]]
        if len(selected) < 2:
            raise ValueError("Analysis interval contains fewer than two video frames")
        times, levels = map(list, zip(*selected))
    ordered = sorted(levels)
    floor = ordered[len(ordered) // 10]
    peak = ordered[9 * len(ordered) // 10]
    if peak - floor < 0.004:
        raise ValueError("No clear green-flash contrast; adjust ROI / lighting / mirror framing")
    video_threshold = floor + 0.4 * (peak - floor)
    video = onsets(times, levels, video_threshold, 0.5)

    raw = command("ffmpeg", "-v", "error", "-i", str(path), "-vn",
                  "-ac", "1", "-ar", "48000", "-f", "s16le", "-")
    samples = array.array("h", raw)
    rate, window = 48000, 480  # 10 ms; report this analysis resolution.
    cosines = [math.cos(2 * math.pi * 1000 * i / rate) for i in range(window)]
    sines = [math.sin(2 * math.pi * 1000 * i / rate) for i in range(window)]
    amplitudes = []
    for start in range(0, len(samples) - window + 1, window):
        block = samples[start:start + window]
        real = sum(v * c for v, c in zip(block, cosines))
        imag = sum(v * s for v, s in zip(block, sines))
        amplitudes.append(2 * math.hypot(real, imag) / window)
    if not amplitudes or max(amplitudes) < 100:
        raise ValueError("No clear 1 kHz test tone")
    audio_threshold = max(amplitudes) * 0.2
    audio_times = [i * window / rate for i in range(len(amplitudes))]
    if interval:
        selected = [(t, level) for t, level in zip(audio_times, amplitudes)
                    if interval[0] <= t < interval[1]]
        if not selected:
            raise ValueError("Analysis interval contains no audio windows")
        audio_times, amplitudes = map(list, zip(*selected))
    audio = onsets(audio_times, amplitudes, audio_threshold, 0.5)
    # Do not silently pair repeated 1-second pulses across an ambiguous whole-period offset.
    # The first pulse anchors the sequence. The first/last two seconds are excluded only
    # from the stable statistics, never used as a synchronization correction.
    pairs = []
    for v, a in zip(video, audio):
        pairs.append({"video_s": v, "audio_s": a, "audio_minus_video_ms": (a - v) * 1000})
    stable = [p for p in pairs if p["video_s"] >= video[0] + 2 and p["video_s"] <= times[-1] - 1] if video else []
    if len(stable) < 3 or len(video) != len(audio):
        raise ValueError("Need >=3 stable pulse pairs with equal counts; inspect clip/start framing manually")
    offsets_ms = [p["audio_minus_video_ms"] for p in stable]
    if max(offsets_ms) - min(offsets_ms) > 300:
        raise ValueError("Pulse pairing / display contrast unstable; inspect events manually")
    frame_period = statistics.median(b - a for a, b in zip(times, times[1:]))
    return {
        "method": "physical_mirror_green_flash_vs_decoded_1kHz_AAC_pulse",
        "positive_offset": "audio later than visible flash",
        "video_onsets_s": video, "audio_onsets_s": audio, "pairs": pairs,
        "stable_pairs": len(stable), "median_offset_ms": statistics.median(offsets_ms),
        "min_offset_ms": min(offsets_ms), "max_offset_ms": max(offsets_ms),
        "drift_first_to_last_ms": offsets_ms[-1] - offsets_ms[0],
        "video_frame_period_ms": frame_period * 1000, "audio_analysis_window_ms": 10,
        "green_roi": roi, "video_threshold": video_threshold,
        "analysis_interval_s": interval,
        "pairing_method": "first onsets within the selected original-PTS interval",
        "absolute_cycle_identity_verified": False,
        "audio_threshold": audio_threshold,
        "limitation": "Includes playback-head/render/Bluetooth/capture/codec effects; "
                      "frame + 10 ms quantization; repeated pulses have no unique cycle ID, "
                      "so missed beginning pulses leave whole-period ambiguity; "
                      "not precise sensor acquisition synchronization",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mp4", type=Path)
    parser.add_argument("--roi", default="0,0,1,1", help="normalized x,y,width,height in auto-rotated video")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--interval", help="original media seconds start,end; manually verify visibility and pulse pairing")
    args = parser.parse_args()
    roi = [float(v) for v in args.roi.split(",")]
    if len(roi) != 4 or min(roi) < 0 or roi[2] <= 0 or roi[3] <= 0 or roi[0]+roi[2] > 1 or roi[1]+roi[3] > 1:
        parser.error("invalid normalized ROI")
    interval = [float(v) for v in args.interval.split(",")] if args.interval else None
    if interval and (len(interval) != 2 or not 0 <= interval[0] < interval[1] <= 120):
        parser.error("invalid analysis interval")
    try:
        result = analyze(args.mp4, roi, interval)
    except (ValueError, subprocess.CalledProcessError) as error:
        result = {"passed": False, "error": str(error)}
    else:
        result["passed"] = True
    report = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(report + "\n")
    print(report)
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
